# TerraWorld Backend Docs

> **API Spec has moved.**
>
> The TerraWorld API specification is now maintained in the
> [`TerraWorld-IT/openapi`](https://github.com/TerraWorld-IT/openapi) repo as
> split OpenAPI 3.0 YAML.
>
> - Human-readable doc (read-only archive):
>   [`openapi/docs/API_SPEC.md`](https://github.com/TerraWorld-IT/openapi/blob/main/docs/API_SPEC.md)
> - Authoritative source: [`openapi/spec/`](https://github.com/TerraWorld-IT/openapi/tree/main/spec)
> - Generated Kotlin Spring stubs (consumed by this repo): [`TerraWorld-IT/openapi-backend`](https://github.com/TerraWorld-IT/openapi-backend)
>
> ## Workflow
>
> 1. Edit split YAML in `TerraWorld-IT/openapi/spec/`
> 2. Open a PR → Redocly lint + bundle run in CI
> 3. Merge to `main` → `sync.yml` regenerates `openapi-backend` (kotlin-spring
>    interfaces) and force-pushes it
> 4. This repo picks up the change via the `openapi-backend` Gradle composite
>    build (`includeBuild`)
> 5. Spring controllers implement the generated interfaces — compile fails if
>    the spec drifts
