# Notification Service — Code Design

This module uses **hexagonal architecture** (ports & adapters). Dependencies point inward:
adapters depend on the application layer, the application depends on the domain.

```
Inbound adapter → Use case → Repository → Domain
                                  │
                                  ▼
                           Outbound port
                                  ▲
                                  │ implements
                           Outbound adapter
```

## Repositories are DDD repositories, not JPA repositories

The classes under `application.repository` are **DDD repositories**. They speak the language of
the domain — load and persist aggregates like `PushersCollection` — and hide all storage details
from use cases.

Do not confuse them with framework repositories (e.g. Spring Data `JpaRepository`) that map
directly to database tables and return persistence entities. Our repositories are part of the
application layer, and their public APIs use domain types.

## The DAO's SQL is vendor-neutral on purpose

The service runs on any of the five engines Keycloak supports, and it does so with **one** set of
statements rather than a dialect per engine. That is affordable only because the port asks so little
of the database: one table of opaque blobs, addressed by primary key, with a compare-and-set token
for concurrency.

Keeping it that way is a constraint on future changes, not just a description of the current one. A
dialect-specific construct added here compiles and passes on PostgreSQL while silently breaking one
of the other four, and the cost of the first one is not the construct itself but the dialect
abstraction it forces on everything after it. If a change seems to need vendor-specific SQL, the
question to ask first is whether the *domain* can absorb it instead — the capped history aggregate
is the precedent: it bounds itself in `DeviceMessageHistory` rather than asking the DAO for
eviction.

## DAOs fetch data; repositories map to domain

Persistence is split into two roles:

| Role                                                        | Layer                                    | Responsibility                                                                                                |
|-------------------------------------------------------------|------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| **DAO** (`StorageRecordDAO`)                                | Outbound port, implemented by an adapter | Read/write opaque `StorageRecord` blobs by storage id. No knowledge of domain models.                         |
| **Repository** (`PushersRepository`, `AggregateRepository`) | Application                              | Serialize/deserialize aggregates, apply cross-cutting concerns (pseudonymization, sealing), and call the DAO. |

**Read path**

```
Use case ──► Repository.findBy(userId)
                 │
                 ├─ pseudonymize id
                 ├─ DAO.findById(storageId) ──► StorageRecord
                 ├─ open sealed payload
                 └─ deserialize ──► PushersCollection
```

**Write path**

```
Use case ──► Repository.save(aggregate)
                 │
                 ├─ serialize aggregate, seal payload
                 └─ DAO.save(storageId, StorageRecord)
```

On read, the DAO returns raw storage records; the repository converts them into domain models. On
write, the repository converts domain models back into storage records before handing them to the
DAO.
