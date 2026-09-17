# GravityEngine

GravityEngine provides reusable gravity fields, movement/collision kernels,
body-attitude control and Minecraft integration. Content such as StarminerR blocks,
world generation and gameplay policy remains external. The identity is
`cc.sighs.gravityengine` / `gravityengine`.

## Supported baseline

| Component | Runtime | Status |
| --- | --- | --- |
| common | Java 17 | Tested kernel and neutral consumer API |
| NeoForge 1.21.1 | Java 21, NeoForge 21.1.256 | Primary integration; API baseline 0.0.2 |
| Optional Sable | 2.0.6 on the primary target | Strict installed/absent server gates |
| Forge / Fabric 1.20.1 | Java 17 runtime; Fabric build launcher Java 25 | Scaffold; integration parity unverified |
| NeoForge 26.1 | Java 25 | Scaffold; integration parity unverified |

The [checkpoint record](docs/REFACTOR_STATUS.md) separates actual verification from
unrun client smoke and whole-game profiling. A successful build is not cross-version
or client gameplay acceptance.

## Build and use

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
```

On POSIX use `bash ./targets/neoforge-1.21.1/gradlew` with the same arguments.
The output is `targets/neoforge-1.21.1/build/libs/GravityEngine-neoforge-1.21.1-0.0.2.jar`.
Use that JAR as a file dependency in a NeoForge 21.1.256 consumer; common is embedded.
Register provider IDs during initialization, create a session per Level, publish
immutable fields and report coverage for each query. Retain and close publication
leases on the source's declared lifecycle. Read the
[compiling provider example](targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ProviderFixture.java)
and [consumer example](targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ExternalConsumerFixture.java).

Only `api`, `api.field` and `api.math` are supported consumer namespaces.
FIELD coverage is per query; an empty registry never proves completeness.
Entity observations distinguish assignment, evaluated gravity and committed
application. DIRECT writes, arbitrary attitude transactions and collision providers
remain internal.

## Documentation

- [API contracts and 0.0.2 migration](docs/API_BOUNDARY.md)
- [Architecture and ownership](docs/ARCHITECTURE.md)
- [Build, verification, CI and publishing](docs/DEVELOPMENT.md)
- [Two-checkpoint evidence](docs/REFACTOR_STATUS.md)
- [Contributor instructions](AGENTS.md)

## License

[GNU GPL version 3](LICENSE). Existing source notices remain in place. This checkout
contains no maintained third-party license index; this document does not invent one
or replace obligations attached to bundled dependencies.
