package com.terraworld.api.record

import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.api.notification.FriendActivityEvent
import com.terraworld.api.notification.HabitPairEvent
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.record.HabitCycle
import com.terraworld.domain.record.HabitCycleRepository
import com.terraworld.domain.record.HabitPairRequest
import com.terraworld.domain.record.HabitPairRequestKind
import com.terraworld.domain.record.HabitPairRequestRepository
import com.terraworld.domain.record.HabitPairRequestStatus
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.HabitTrackerResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 습관(7일 트래커) 서비스 — 아프젝 v2 상태머신 (§R2).
 *
 * - 활성(PENDING/ACTIVE/COMPLETED_UNCLAIMED) 습관은 solo·friend 합산 1개 (409 HABIT_LIMIT_EXCEEDED).
 * - 친구 습관: 요청자 트래커 + 상대 미러 트래커(둘 다 PENDING) + [HabitPairRequest](START). 수락 시 양측 ACTIVE,
 *   거절/취소/만료 시 양측 BROKEN. 수락 전 체크인은 409 HABIT_NOT_ACTIVE.
 * - 7일째 체크인 → COMPLETED_UNCLAIMED(보상 미지급). complete(종료) / extend(새 사이클) 가 보상을 지급한다 —
 *   CAS 상태 전이 + user_grants 원장 키 `habit:{cycleId}:reward:{userId}` 로 사이클당 1회(재호출은 alreadyClaimed 재생).
 * - 중단(DELETE): 본인만 BROKEN, 상대는 유지 + PARTNER_STOPPED(보상 solo 값). PENDING 요청자의 DELETE 는 취소.
 * - 보상: solo 100 / pair 200 (설정 habit.reward.*). 알림 HABIT 4이벤트는 [HabitPairEvent] AFTER_COMMIT.
 * 설계 SoT: docs/root/plans/2026-08-23-apjek-v2-api-contract.md §R2
 */
@Service
class HabitService(
    private val habitTrackerRepository: HabitTrackerRepository,
    private val habitCycleRepository: HabitCycleRepository,
    private val habitPairRequestRepository: HabitPairRequestRepository,
    private val grantService: GrantService,
    private val inviteRepository: InviteRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: HabitProperties = HabitProperties(),
) {
    companion object {
        const val REASON_HABIT_CYCLE = "HABIT_CYCLE"
        const val REF_TYPE = "HABIT"
        const val ROUTE_RECORD = "/record"
    }

    // ─── 생성 / 목록 ───────────────────────────────────────────

    @Transactional
    fun createTracker(
        userId: String,
        title: String,
        friendUserId: String? = null,
    ): HabitTracker {
        require(title.isNotBlank()) { "title 은 공백 불가" }
        val trimmedTitle = title.trim()
        val today = KstTime.today()

        if (friendUserId.isNullOrBlank()) {
            lockUser(userId)
            ensureNoOpenHabit(userId)
            val tracker =
                habitTrackerRepository.save(
                    HabitTracker(userId = userId, title = trimmedTitle, startDate = today, status = HabitStatus.ACTIVE),
                )
            openCycle(tracker, cycleNo = 1, startedOn = today)
            return habitTrackerRepository.save(tracker)
        }

        if (friendUserId == userId) throw BusinessException(ErrorCode.INVALID_INPUT, "본인과는 함께할 수 없어요")
        // 친구 함께 습관: friendUserId 를 수락된 invite 로 검증해 연동 ID(invite.id)로 해석 (무검증 임의 link 차단).
        val linkId =
            inviteRepository
                .findAcceptedBetween(userId, friendUserId)
                .map { it.id }
                .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT, "수락된 친구 관계가 아닙니다") }

        // 양측 활성 1개 제한을 per-user advisory lock 으로 직렬화 — 정렬 순서로 획득해 교착 회피.
        listOf(userId, friendUserId).sorted().forEach { lockUser(it) }
        ensureNoOpenHabit(userId)
        if (habitTrackerRepository.countByUserIdAndStatusIn(friendUserId, HabitStatus.OPEN) > 0) {
            throw BusinessException(ErrorCode.HABIT_LIMIT_EXCEEDED, "상대가 이미 진행 중인 습관이 있어요")
        }

        val mine =
            habitTrackerRepository.save(
                HabitTracker(userId = userId, title = trimmedTitle, startDate = today, status = HabitStatus.PENDING, friendLinkId = linkId),
            )
        val mirror =
            habitTrackerRepository.save(
                HabitTracker(userId = friendUserId, title = trimmedTitle, startDate = today, status = HabitStatus.PENDING, friendLinkId = linkId),
            )
        mine.partnerTrackerId = mirror.id
        mirror.partnerTrackerId = mine.id
        habitTrackerRepository.save(mirror)
        val saved = habitTrackerRepository.save(mine)

        habitPairRequestRepository.save(
            HabitPairRequest(
                requesterTrackerId = saved.id,
                requesterUserId = userId,
                partnerTrackerId = mirror.id,
                partnerUserId = friendUserId,
                kind = HabitPairRequestKind.START,
                expiresAt = LocalDateTime.now().plusDays(properties.requestExpiryDays),
            ),
        )
        notify(
            fromUserId = userId,
            toUserId = friendUserId,
            title = "함께 습관 요청이 도착했어요",
            body = "${nicknameOf(userId)} 님이 「$trimmedTitle」 습관을 함께 하자고 요청했어요. 기록하기에서 수락해 보세요",
        )
        return saved
    }

    /** 열린(PENDING/ACTIVE/COMPLETED_UNCLAIMED) 트래커 목록. 만료된 요청은 조회 시 lazy 로 EXPIRED → BROKEN 처리 후 제외. */
    @Transactional
    fun listOpen(userId: String): List<HabitTracker> {
        val trackers = habitTrackerRepository.findAllByUserIdAndStatusIn(userId, HabitStatus.OPEN)
        expireStaleRequests(trackers)
        return trackers.filter { it.status in HabitStatus.OPEN }
    }

    // ─── 체크인 ───────────────────────────────────────────────

    /**
     * 오늘 체크인. 연속이면 streak++, 하루 이상 공백이면 streak=1 재시작. 같은 날 재체크인은 멱등 no-op.
     * 7일째 도달 시 COMPLETED_UNCLAIMED 로 전이(보상 미지급, cycleCompleted=true) — 보상은 complete/extend.
     * ACTIVE 가 아니면(PENDING 수락 대기 / 완주 대기 / 종료) 409 HABIT_NOT_ACTIVE.
     */
    @Transactional
    fun checkIn(
        userId: String,
        trackerId: Long,
    ): HabitCheckInResult {
        val tracker =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (tracker.status == HabitStatus.PENDING) expireStaleRequests(listOf(tracker))
        if (tracker.status != HabitStatus.ACTIVE) throw BusinessException(ErrorCode.HABIT_NOT_ACTIVE)

        val today = KstTime.today()
        if (tracker.lastCheckedDate == today) {
            // 같은 날 재체크인 — 멱등
            return HabitCheckInResult(tracker, cycleCompleted = false)
        }

        // 연속 판정: 어제 체크인했으면 streak++, 아니면(공백/최초) 1 로 재시작
        tracker.currentStreakDays =
            if (tracker.lastCheckedDate == today.minusDays(1)) tracker.currentStreakDays + 1 else 1
        tracker.lastCheckedDate = today

        var cycleCompleted = false
        if (tracker.currentStreakDays >= tracker.cycleLengthDays) {
            // 7일째: 완주 — 보상은 지급하지 않고 complete/extend 호출을 기다린다.
            cycleCompleted = true
            val now = LocalDateTime.now()
            tracker.status = HabitStatus.COMPLETED_UNCLAIMED
            tracker.cycleCompletedAt = now
            currentCycleOf(tracker)?.let {
                it.completedAt = now
                habitCycleRepository.save(it)
            }
        }
        habitTrackerRepository.save(tracker)

        // 친구 연동 습관 — 체크인/완주를 상대에게 알림 (낙서장 "트래커 기록 시 상대 알림"). 같은 날 재체크인은 조기 return 이라 최대 1회/일.
        partnerOf(tracker)?.takeIf { it.status in HabitStatus.OPEN }?.let { partner ->
            val nickname = nicknameOf(userId)
            val message =
                if (cycleCompleted) {
                    "$nickname 님이 「${tracker.title}」 습관 ${tracker.cycleLengthDays}일을 완주했어요! 🎉"
                } else {
                    "$nickname 님이 「${tracker.title}」 습관을 체크인했어요 (${tracker.currentStreakDays}일째)"
                }
            eventPublisher.publishEvent(FriendActivityEvent(fromUserId = userId, toUserId = partner.userId, message = message))
        }

        return HabitCheckInResult(tracker, cycleCompleted)
    }

    // ─── 페어 요청 수락 / 거절 / 중단 ─────────────────────────────

    /**
     * 수신자 자신의 미러 트래커 id 로 수락. START: 양측 ACTIVE + 사이클 개시. EXTEND: 수신자는 완주(COMPLETED_UNCLAIMED) 상태여야
     * 하며 자기 보상 수령 + 양측 새 사이클 ACTIVE. 수락할 열린 요청이 없거나 만료면 409 HABIT_INVALID_STATE.
     */
    @Transactional
    fun accept(
        userId: String,
        trackerId: Long,
    ): HabitTracker {
        val mine =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        val request = openRequestReceivedBy(mine.id) ?: throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
        val now = LocalDateTime.now()
        if (request.isExpired(now)) {
            expire(request)
            throw BusinessException(ErrorCode.HABIT_INVALID_STATE, "만료된 요청이에요")
        }
        val requester =
            habitTrackerRepository.findById(request.requesterTrackerId).orElse(null)
                ?: throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
        if (requester.status != HabitStatus.PENDING) throw BusinessException(ErrorCode.HABIT_INVALID_STATE)

        val today = KstTime.today()
        when (request.kind) {
            HabitPairRequestKind.START -> {
                if (mine.status != HabitStatus.PENDING) throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
                mine.status = HabitStatus.ACTIVE
                requester.status = HabitStatus.ACTIVE
                openCycle(mine, mine.completedCycles + 1, today)
                openCycle(requester, requester.completedCycles + 1, today)
            }
            HabitPairRequestKind.EXTEND -> {
                acceptExtend(mine, requester, today)
            }
        }
        request.status = HabitPairRequestStatus.ACCEPTED
        request.respondedAt = now
        habitPairRequestRepository.save(request)
        habitTrackerRepository.save(requester)
        val saved = habitTrackerRepository.save(mine)

        val body =
            if (request.kind == HabitPairRequestKind.START) {
                "${nicknameOf(userId)} 님이 「${mine.title}」 습관 요청을 수락했어요. 오늘부터 함께 시작해요"
            } else {
                "${nicknameOf(userId)} 님이 「${mine.title}」 습관 연장을 수락했어요. 새 7일이 시작됐어요"
            }
        notify(userId, requester.userId, "습관 요청이 수락됐어요", body)
        return saved
    }

    /** EXTEND 수락 — 수신자는 완주 상태여야 하며 자기 보상을 수령하고 양측 새 사이클을 연다. */
    private fun acceptExtend(
        mine: HabitTracker,
        requester: HabitTracker,
        today: LocalDate,
    ) {
        if (mine.status != HabitStatus.COMPLETED_UNCLAIMED) {
            throw BusinessException(ErrorCode.HABIT_INVALID_STATE, "이번 사이클을 완주한 뒤 연장을 수락할 수 있어요")
        }
        claimReward(mine)
        startNextCycle(mine, today)
        mine.status = HabitStatus.ACTIVE
        requester.status = HabitStatus.ACTIVE
    }

    /**
     * 수신자 자신의 미러 트래커 id 로 거절. START: 양측 BROKEN. EXTEND: 요청자 트래커만 BROKEN(수신자는 자기 사이클 유지 — 보상 수령 가능).
     * 거절할 열린 요청이 없으면 409 HABIT_INVALID_STATE.
     */
    @Transactional
    fun decline(
        userId: String,
        trackerId: Long,
    ) {
        val mine =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        val request = openRequestReceivedBy(mine.id) ?: throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
        val now = LocalDateTime.now()
        if (request.isExpired(now)) {
            expire(request)
            throw BusinessException(ErrorCode.HABIT_INVALID_STATE, "만료된 요청이에요")
        }
        closeRequest(request, HabitPairRequestStatus.DECLINED, mine, now)
        notify(userId, request.requesterUserId, "😢 습관 요청이 거절됐어요", "${nicknameOf(userId)} 님이 「${mine.title}」 습관 요청을 거절했어요")
    }

    /**
     * 습관 종료 (DELETE). PENDING 요청자 → 요청 취소(START 양측 BROKEN / EXTEND 본인만 BROKEN), PENDING 수신자 → 거절과 동일.
     * ACTIVE/COMPLETED_UNCLAIMED → 본인만 BROKEN, 상대는 유지 + PARTNER_STOPPED 알림. 이미 종료된 트래커는 멱등 no-op.
     */
    @Transactional
    fun stop(
        userId: String,
        trackerId: Long,
    ) {
        val tracker =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        when (tracker.status) {
            HabitStatus.PENDING -> {
                val now = LocalDateTime.now()
                val sent = openRequestSentBy(tracker.id)
                val received = if (sent == null) openRequestReceivedBy(tracker.id) else null
                when {
                    sent != null -> {
                        closeRequest(sent, HabitPairRequestStatus.CANCELLED, tracker, now)
                        notify(userId, sent.partnerUserId, "습관 요청이 취소됐어요", "${nicknameOf(userId)} 님이 「${tracker.title}」 습관 요청을 취소했어요")
                    }
                    received != null -> {
                        closeRequest(received, HabitPairRequestStatus.DECLINED, tracker, now)
                        notify(userId, received.requesterUserId, "😢 습관 요청이 거절됐어요", "${nicknameOf(userId)} 님이 「${tracker.title}」 습관 요청을 거절했어요")
                    }
                    else -> habitTrackerRepository.markBroken(trackerId, userId) // 열린 요청이 없는 고아 PENDING — 그냥 종료
                }
            }
            HabitStatus.ACTIVE, HabitStatus.COMPLETED_UNCLAIMED -> {
                // Codex R1 #8: read-then-save 는 동시 DELETE 시 @Version 낙관락 충돌로 한쪽이 500 — 조건부 UPDATE 로 멱등.
                val updated = habitTrackerRepository.markBroken(trackerId, userId)
                if (updated == 1) {
                    partnerOf(tracker)?.takeIf { it.status in HabitStatus.OPEN }?.let { partner ->
                        notify(
                            userId,
                            partner.userId,
                            "친구가 습관을 중단했어요",
                            "${nicknameOf(userId)} 님이 「${tracker.title}」 습관을 중단했어요. 혼자서도 계속 이어갈 수 있어요 (보상은 solo 기준)",
                        )
                    }
                }
            }
            HabitStatus.COMPLETED, HabitStatus.BROKEN -> Unit // 이미 종료 — 멱등
        }
    }

    // ─── 완주 보상 (complete / extend) ─────────────────────────────

    /**
     * 보상 수령 + 카드 종료. COMPLETED_UNCLAIMED → COMPLETED CAS 후 사이클 원장 키로 1회 지급.
     * 이미 COMPLETED(지급 완료)면 200 멱등 재생(alreadyClaimed=true, sparkle 0). 그 외 상태는 409 HABIT_INVALID_STATE.
     */
    @Transactional
    fun complete(
        userId: String,
        trackerId: Long,
    ): HabitRewardResult {
        val tracker =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (tracker.status == HabitStatus.COMPLETED) {
            return HabitRewardResult(tracker, sparkleGranted = 0, alreadyClaimed = true)
        }
        if (tracker.status != HabitStatus.COMPLETED_UNCLAIMED) throw BusinessException(ErrorCode.HABIT_INVALID_STATE)

        val rows = habitTrackerRepository.casStatus(trackerId, userId, HabitStatus.COMPLETED_UNCLAIMED, HabitStatus.COMPLETED)
        // CAS(bulk UPDATE, clearAutomatically) 이후 영속성 컨텍스트가 비워지므로 재조회한다.
        val fresh = habitTrackerRepository.findByIdAndUserId(trackerId, userId) ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (rows == 0) {
            if (fresh.status == HabitStatus.COMPLETED) return HabitRewardResult(fresh, 0, alreadyClaimed = true)
            throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
        }
        val claim = claimReward(fresh)
        fresh.completedCycles += 1
        val saved = habitTrackerRepository.save(fresh)
        return HabitRewardResult(saved, claim.granted, claim.alreadyClaimed)
    }

    /**
     * 보상 수령 + 같은 제목·친구 설정으로 새 7일 사이클. solo(또는 상대가 종료됨) 는 즉시 ACTIVE,
     * friend 는 EXTEND 요청(REQUESTED) + 내 트래커 PENDING. 상대가 먼저 보낸 연장 요청이 열려 있으면 그 수락으로 처리한다.
     * COMPLETED_UNCLAIMED 가 아니면 409 HABIT_INVALID_STATE.
     */
    @Transactional
    fun extend(
        userId: String,
        trackerId: Long,
    ): HabitRewardResult {
        val tracker =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (tracker.status != HabitStatus.COMPLETED_UNCLAIMED) throw BusinessException(ErrorCode.HABIT_INVALID_STATE)
        val today = KstTime.today()

        // 상대가 먼저 연장을 요청해 둔 상태 — 내 extend 는 곧 수락이다 (양측 새 사이클 ACTIVE).
        val incoming = openRequestReceivedBy(tracker.id)
        if (incoming != null && incoming.kind == HabitPairRequestKind.EXTEND && !incoming.isExpired(LocalDateTime.now())) {
            val requester = habitTrackerRepository.findById(incoming.requesterTrackerId).orElse(null)
            if (requester != null && requester.status == HabitStatus.PENDING) {
                val claim = claimReward(tracker)
                startNextCycle(tracker, today)
                tracker.status = HabitStatus.ACTIVE
                requester.status = HabitStatus.ACTIVE
                incoming.status = HabitPairRequestStatus.ACCEPTED
                incoming.respondedAt = LocalDateTime.now()
                habitPairRequestRepository.save(incoming)
                habitTrackerRepository.save(requester)
                val saved = habitTrackerRepository.save(tracker)
                notify(userId, requester.userId, "습관 연장이 수락됐어요", "${nicknameOf(userId)} 님도 「${tracker.title}」 습관을 연장했어요. 새 7일이 시작됐어요")
                return HabitRewardResult(saved, claim.granted, claim.alreadyClaimed)
            }
        }

        val partner = partnerOf(tracker)
        // 상대가 아직 함께(열린 상태)면 연장 요청, 종료(COMPLETED/BROKEN)됐으면 solo 연장
        val pairContinues = partner != null && partner.status in HabitStatus.OPEN
        val target = if (pairContinues) HabitStatus.PENDING else HabitStatus.ACTIVE
        val rows = habitTrackerRepository.casStatus(trackerId, userId, HabitStatus.COMPLETED_UNCLAIMED, target)
        val fresh = habitTrackerRepository.findByIdAndUserId(trackerId, userId) ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (rows == 0) throw BusinessException(ErrorCode.HABIT_INVALID_STATE)

        val claim = claimReward(fresh)
        startNextCycle(fresh, today)
        if (pairContinues && partner != null) {
            habitPairRequestRepository.save(
                HabitPairRequest(
                    requesterTrackerId = fresh.id,
                    requesterUserId = userId,
                    partnerTrackerId = partner.id,
                    partnerUserId = partner.userId,
                    kind = HabitPairRequestKind.EXTEND,
                    expiresAt = LocalDateTime.now().plusDays(properties.requestExpiryDays),
                ),
            )
            notify(
                userId,
                partner.userId,
                "🔁 습관 연장 요청이 도착했어요",
                "${nicknameOf(userId)} 님이 「${fresh.title}」 습관을 7일 더 함께 하자고 해요. 완주 후 수락하면 새 사이클이 시작돼요",
            )
        } else {
            // 상대가 종료됐으면 solo 로 — 연동 해제
            fresh.partnerTrackerId = null
            fresh.friendLinkId = null
        }
        val saved = habitTrackerRepository.save(fresh)
        return HabitRewardResult(saved, claim.granted, claim.alreadyClaimed)
    }

    // ─── 응답 뷰 (partnerStatus / extendStatus / rewardSparkle) ────────

    /**
     * 응답용 페어 뷰 배치 해석 (N+1 회피). partnerStatus/extendStatus 는 요청 원장 + 상대 트래커 상태의 파생 뷰,
     * rewardSparkle 은 완주 시 지급(예정) 반짝이(상대 종료 시 solo 값).
     */
    @Transactional(readOnly = true)
    fun resolveView(
        userId: String,
        trackers: List<HabitTracker>,
    ): Map<Long, HabitView> {
        if (trackers.isEmpty()) return emptyMap()
        val today = KstTime.today()
        val ids = trackers.map { it.id }
        val partnerIds = trackers.mapNotNull { it.partnerTrackerId }.distinct()
        val partnersById =
            if (partnerIds.isEmpty()) emptyMap() else habitTrackerRepository.findAllById(partnerIds).associateBy { it.id }
        val requests = habitPairRequestRepository.findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(ids, ids)
        val nicknameById =
            partnersById.values
                .map { it.userId }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { userRepository.findAllById(it).associate { u -> u.id to u.nickname } }
                .orEmpty()

        return trackers.associate { t ->
            val partner = t.partnerTrackerId?.let { partnersById[it] }
            val related = requests.filter { it.requesterTrackerId == t.id || it.partnerTrackerId == t.id }.sortedBy { it.id }
            val latestStart = related.lastOrNull { it.kind == HabitPairRequestKind.START }
            val latestExtend = related.lastOrNull { it.kind == HabitPairRequestKind.EXTEND }

            val partnerStatus =
                when {
                    partner == null -> HabitTrackerResponse.PartnerStatus.NONE
                    latestStart?.isOpen() == true ->
                        if (latestStart.requesterTrackerId == t.id) {
                            HabitTrackerResponse.PartnerStatus.PENDING_SENT
                        } else {
                            HabitTrackerResponse.PartnerStatus.PENDING_RECEIVED
                        }
                    partner.status == HabitStatus.BROKEN ->
                        when (latestStart?.status) {
                            HabitPairRequestStatus.DECLINED -> HabitTrackerResponse.PartnerStatus.DECLINED
                            HabitPairRequestStatus.CANCELLED -> HabitTrackerResponse.PartnerStatus.CANCELLED
                            HabitPairRequestStatus.EXPIRED -> HabitTrackerResponse.PartnerStatus.EXPIRED
                            else -> HabitTrackerResponse.PartnerStatus.PARTNER_STOPPED
                        }
                    else -> HabitTrackerResponse.PartnerStatus.ACCEPTED
                }
            val extendStatus =
                when {
                    partner == null || latestExtend == null -> HabitTrackerResponse.ExtendStatus.NONE
                    latestExtend.isOpen() ->
                        if (latestExtend.requesterTrackerId == t.id) {
                            HabitTrackerResponse.ExtendStatus.PENDING_SENT
                        } else {
                            HabitTrackerResponse.ExtendStatus.PENDING_RECEIVED
                        }
                    latestExtend.status == HabitPairRequestStatus.ACCEPTED && t.status == HabitStatus.ACTIVE ->
                        HabitTrackerResponse.ExtendStatus.ACCEPTED
                    else -> HabitTrackerResponse.ExtendStatus.NONE
                }
            t.id to
                HabitView(
                    friendUserId = partner?.userId,
                    friendNickname = partner?.let { nicknameById[it.userId] },
                    partnerStatus = partnerStatus,
                    extendStatus = extendStatus,
                    rewardSparkle = rewardFor(partner),
                    // 상대가 오늘(KST) 체크인했는가 — 페어가 살아 있을 때만(solo / 상대 중단 BROKEN 은 false).
                    partnerCheckedToday = partner != null && partner.status != HabitStatus.BROKEN && partner.lastCheckedDate == today,
                )
        }
    }

    // ─── 내부 헬퍼 ──────────────────────────────────────────────

    private fun lockUser(userId: String) {
        habitTrackerRepository.acquireHabitPairLock("habit|user|$userId")
    }

    private fun ensureNoOpenHabit(userId: String) {
        if (habitTrackerRepository.countByUserIdAndStatusIn(userId, HabitStatus.OPEN) > 0) {
            throw BusinessException(ErrorCode.HABIT_LIMIT_EXCEEDED)
        }
    }

    private fun openCycle(
        tracker: HabitTracker,
        cycleNo: Int,
        startedOn: LocalDate,
    ): HabitCycle {
        val cycle =
            habitCycleRepository.save(
                HabitCycle(trackerId = tracker.id, userId = tracker.userId, cycleNo = cycleNo, startedOn = startedOn),
            )
        tracker.currentCycleId = cycle.id
        return cycle
    }

    /** 완주한 사이클을 닫고(completedCycles+1, streak 0) 새 사이클을 연다. lastCheckedDate 는 유지 — 같은 날 1일차 중복 체크 방지. */
    private fun startNextCycle(
        tracker: HabitTracker,
        today: LocalDate,
    ) {
        tracker.completedCycles += 1
        tracker.currentStreakDays = 0
        tracker.cycleCompletedAt = null
        openCycle(tracker, tracker.completedCycles + 1, today)
    }

    private fun currentCycleOf(tracker: HabitTracker): HabitCycle? = tracker.currentCycleId?.let { habitCycleRepository.findById(it).orElse(null) }

    private fun partnerOf(tracker: HabitTracker): HabitTracker? = tracker.partnerTrackerId?.let { habitTrackerRepository.findById(it).orElse(null) }

    /** 완주 보상액: 상대가 있고 종료(BROKEN)되지 않았으면 pair, 아니면 solo. */
    private fun rewardFor(partner: HabitTracker?): Long = if (partner != null && partner.status != HabitStatus.BROKEN) properties.reward.partnerSparkle else properties.reward.soloSparkle

    /**
     * 사이클 보상 1회 지급 — user_grants 원장 키 `habit:{cycleId}:reward:{userId}` (GrantService CURRENCY → SPARKLE credit 원자).
     * 이미 지급된 키면 grant 가 false → alreadyClaimed. V39 이전 트래커(사이클 행 없음)는 지금 사이클 행을 만들어 키를 확보한다.
     */
    private fun claimReward(tracker: HabitTracker): ClaimResult {
        val cycle = currentCycleOf(tracker) ?: openCycle(tracker, tracker.completedCycles + 1, tracker.startDate)
        val amount = rewardFor(partnerOf(tracker))
        val granted =
            grantService.grant(
                tracker.userId,
                GrantType.CURRENCY,
                CurrencyCode.SPARKLE,
                amount,
                HabitCycle.rewardKey(cycle.id, tracker.userId),
                REASON_HABIT_CYCLE,
            )
        if (granted) {
            cycle.rewardClaimedAt = LocalDateTime.now()
            cycle.rewardSparkle = amount
            habitCycleRepository.save(cycle)
        }
        return ClaimResult(granted = if (granted) amount else 0, alreadyClaimed = !granted)
    }

    private fun openRequestReceivedBy(trackerId: Long): HabitPairRequest? =
        habitPairRequestRepository
            .findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(emptyList(), listOf(trackerId))
            .filter { it.partnerTrackerId == trackerId && it.isOpen() }
            .maxByOrNull { it.id }

    private fun openRequestSentBy(trackerId: Long): HabitPairRequest? =
        habitPairRequestRepository
            .findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(listOf(trackerId), emptyList())
            .filter { it.requesterTrackerId == trackerId && it.isOpen() }
            .maxByOrNull { it.id }

    /** 열린 요청을 닫고 트래커를 종료한다 — START 는 양측 BROKEN, EXTEND 는 요청자만 BROKEN. */
    private fun closeRequest(
        request: HabitPairRequest,
        status: HabitPairRequestStatus,
        loaded: HabitTracker?,
        now: LocalDateTime,
    ) {
        request.status = status
        request.respondedAt = now
        habitPairRequestRepository.save(request)
        val requester = loaded?.takeIf { it.id == request.requesterTrackerId } ?: habitTrackerRepository.findById(request.requesterTrackerId).orElse(null)
        requester?.takeIf { it.status in HabitStatus.OPEN }?.let {
            it.status = HabitStatus.BROKEN
            habitTrackerRepository.save(it)
        }
        if (request.kind == HabitPairRequestKind.START) {
            val partnerId = request.partnerTrackerId ?: return
            val partner = loaded?.takeIf { it.id == partnerId } ?: habitTrackerRepository.findById(partnerId).orElse(null)
            partner?.takeIf { it.status in HabitStatus.OPEN }?.let {
                it.status = HabitStatus.BROKEN
                habitTrackerRepository.save(it)
            }
        }
    }

    private fun expire(request: HabitPairRequest) {
        closeRequest(request, HabitPairRequestStatus.EXPIRED, null, LocalDateTime.now())
    }

    /** 트래커 집합이 관여한 열린 요청 중 만료된 것을 EXPIRED 로 닫고 트래커를 BROKEN 처리 (읽기 시 lazy 만료). */
    private fun expireStaleRequests(trackers: List<HabitTracker>) {
        if (trackers.isEmpty()) return
        val ids = trackers.map { it.id }
        val now = LocalDateTime.now()
        val byId = trackers.associateBy { it.id }
        habitPairRequestRepository
            .findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(ids, ids)
            .filter { it.isExpired(now) }
            .forEach { req ->
                closeRequest(req, HabitPairRequestStatus.EXPIRED, byId[req.requesterTrackerId] ?: req.partnerTrackerId?.let { byId[it] }, now)
                // 같은 tx 안의 로드된 엔티티도 상태를 맞춘다 (closeRequest 가 다른 인스턴스를 갱신했을 수 있음)
                val loadedRequester = byId[req.requesterTrackerId]
                if (loadedRequester != null && loadedRequester.status in HabitStatus.OPEN) loadedRequester.status = HabitStatus.BROKEN
                val loadedPartner = req.partnerTrackerId?.let { byId[it] }
                if (req.kind == HabitPairRequestKind.START && loadedPartner != null && loadedPartner.status in HabitStatus.OPEN) {
                    loadedPartner.status = HabitStatus.BROKEN
                }
            }
    }

    private fun nicknameOf(userId: String): String = userRepository.findById(userId).map { it.nickname }.orElse("친구")

    private fun notify(
        fromUserId: String,
        toUserId: String,
        title: String,
        body: String,
    ) {
        eventPublisher.publishEvent(HabitPairEvent(fromUserId = fromUserId, toUserId = toUserId, title = title, body = body, route = ROUTE_RECORD))
    }

    private data class ClaimResult(
        val granted: Long,
        val alreadyClaimed: Boolean,
    )
}

/** 응답용 페어 뷰 (상대 userId/닉네임 + partnerStatus/extendStatus/rewardSparkle + 상대 오늘 체크인 여부). */
data class HabitView(
    val friendUserId: String?,
    val friendNickname: String?,
    val partnerStatus: HabitTrackerResponse.PartnerStatus,
    val extendStatus: HabitTrackerResponse.ExtendStatus,
    val rewardSparkle: Long,
    val partnerCheckedToday: Boolean = false,
)

data class HabitCheckInResult(
    val tracker: HabitTracker,
    val cycleCompleted: Boolean,
)

data class HabitRewardResult(
    val tracker: HabitTracker,
    val sparkleGranted: Long,
    val alreadyClaimed: Boolean,
)
