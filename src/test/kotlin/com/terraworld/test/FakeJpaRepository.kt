package com.terraworld.test

import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.FluentQuery
import java.util.Optional
import java.util.function.Function

/**
 * Spring context 없이 Service 단위 테스트를 돌리기 위한 경량 in-memory JpaRepository.
 *
 * 하위 타입은 도메인별 custom finder 만 override 하면 된다. id 생성은
 * [assignId] 가 엔티티 자체에 적절히 세팅하도록 구현체 책임. 엔티티가 Long
 * auto-generated id 를 가지면 reflection 으로 id 필드를 찾아 쓴다.
 */
abstract class FakeJpaRepository<T : Any, ID : Any> : JpaRepository<T, ID> {

    protected val store: MutableMap<ID, T> = linkedMapOf()

    /** 엔티티에서 id 를 뽑는 추출 함수. 하위 타입에서 override. */
    abstract fun extractId(entity: T): ID

    /** id 가 0 또는 null 상태인 엔티티에 새 id 를 할당. 생성 시 1회만. */
    open fun assignId(entity: T): T = entity

    fun all(): List<T> = store.values.toList()

    override fun findAll(): List<T> = store.values.toList()
    override fun findAll(sort: Sort): List<T> = store.values.toList()
    override fun findAll(pageable: Pageable): Page<T> = Page.empty()
    override fun findAllById(ids: Iterable<ID>): List<T> =
        ids.mapNotNull { store[it] }

    override fun count(): Long = store.size.toLong()

    override fun deleteById(id: ID) { store.remove(id) }
    override fun delete(entity: T) { store.remove(extractId(entity)) }
    override fun deleteAllById(ids: Iterable<ID>) = ids.forEach { store.remove(it) }
    override fun deleteAll(entities: Iterable<T>) = entities.forEach { store.remove(extractId(it)) }
    override fun deleteAll() { store.clear() }

    override fun findById(id: ID): Optional<T> = Optional.ofNullable(store[id])
    override fun existsById(id: ID): Boolean = store.containsKey(id)

    override fun <S : T> save(entity: S): S {
        val withId = assignId(entity)
        @Suppress("UNCHECKED_CAST")
        val cast = withId as S
        store[extractId(cast)] = cast
        return cast
    }

    override fun <S : T> saveAll(entities: Iterable<S>): List<S> = entities.map { save(it) }

    override fun flush() {}
    override fun <S : T> saveAndFlush(entity: S): S = save(entity)
    override fun <S : T> saveAllAndFlush(entities: Iterable<S>): List<S> = entities.map(::save)
    override fun deleteAllInBatch(entities: Iterable<T>) = deleteAll(entities)
    override fun deleteAllByIdInBatch(ids: Iterable<ID>) = deleteAllById(ids)
    override fun deleteAllInBatch() = deleteAll()

    @Deprecated("Deprecated in JpaRepository")
    override fun getOne(id: ID): T = store[id] ?: error("not found: $id")
    @Deprecated("Deprecated in JpaRepository")
    override fun getById(id: ID): T = store[id] ?: error("not found: $id")
    override fun getReferenceById(id: ID): T = store[id] ?: error("not found: $id")

    override fun <S : T> findOne(example: Example<S>): Optional<S> = Optional.empty()
    override fun <S : T> findAll(example: Example<S>): List<S> = emptyList()
    override fun <S : T> findAll(example: Example<S>, sort: Sort): List<S> = emptyList()
    override fun <S : T> findAll(example: Example<S>, pageable: Pageable): Page<S> = Page.empty()
    override fun <S : T> count(example: Example<S>): Long = 0
    override fun <S : T> exists(example: Example<S>): Boolean = false
    override fun <S : T, R : Any> findBy(
        example: Example<S>,
        queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
    ): R = error("unsupported in fake")
}
