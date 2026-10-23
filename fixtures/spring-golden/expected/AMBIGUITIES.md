# Ambiguities in `plugin/schemas/entrypoint-rules.md`

Cases in this fixture where the rules allow more than one reading, and the reading the
expected output uses (the most literal one). Paths are relative to `fixtures/spring-golden/`.

## Values and status

- `src/main/java/com/acme/care/billing/web/BillingAdminController.java`, `downloadStatement`:
  `produces = MediaType.APPLICATION_PDF_VALUE` is a Spring constant outside the source set and
  feeds only `attrs`, not the target. "UNKNOWN: at least one value could not be evaluated" does
  not limit itself to the target. **Chosen:** `UNKNOWN`, with
  `attrs.produces = <MediaType.APPLICATION_PDF_VALUE>`. Real code uses `MediaType` constants
  everywhere, so this reading downgrades many records; the rules should say which values count.
- `src/main/java/com/acme/care/legacy/web/LegacyReportController.java`, `auditTrail`: how an
  unresolvable method-level path joins a resolved prefix. **Chosen:** the expression becomes
  the segment `<PlatformPaths.AUDIT_TRAIL>` and is joined with exactly one `/` like any path:
  `/api/legacy/${care.legacy.report-version:v1}/reports/<PlatformPaths.AUDIT_TRAIL>`,
  `placeholder: true`, `UNKNOWN`.
- `src/main/java/com/acme/care/legacy/messaging/LegacyHl7Listener.java`, `onAcknowledgement`:
  `<bindings>` uses the same angle brackets as an unresolvable value. **Chosen:** it is a fixed
  token, so the status is `INFERRED` (as the table says), not `UNKNOWN`.
- `src/main/java/com/acme/care/shared/events/StartupReadinessListener.java`: the
  `ApplicationListener` row does not say whether the record is `STATIC` or `INFERRED`, and `T`
  (`ApplicationReadyEvent`) is outside the source set. **Chosen:** `STATIC`; the simple name
  needs no resolution.
- `src/main/java/com/acme/care/shared/events/DomainEventHandler.java` (used by
  `ShiftHandoverService.onShiftClosed`) and `src/main/java/com/acme/care/crm/web/CrmGet.java`
  (used by `CrmContactController.listContacts` / `getContact`): a `meta:` tag alone is not in
  the `INFERRED` list. **Chosen:** `STATIC`, `anno` = the composed annotation as written.
- `src/main/java/com/acme/care/crm/web/CrmContactController.java`, `listContacts`: `@CrmGet`
  without a value takes the default `{}` through `@AliasFor`. **Chosen:** empty method path,
  target = class path `/api/crm/contacts`, no `convention-alias` tag.
- `src/main/java/com/acme/care/crm/web/CrmWebConfiguration.java`, nested
  `CrmMaintenanceController`: only the class-level prefix (`ADMIN`) is resolved through a
  static wildcard import; the method path `REINDEX` comes from an enclosing-type field.
  **Chosen:** both records are `INFERRED` with `wildcard-import`, because each target uses
  the wildcard-resolved prefix.
- `src/main/java/com/acme/care/resident/web/AbstractResidentController.java`:
  `@RequestMapping(ResidentPaths.BY_ID)` is a qualified reference whose initialiser
  (`BASE + "/{residentId}"`, `BASE = ApiPaths.API + "/residents"`) lives in another file.
  **Chosen:** each initialiser is evaluated in its declaring type's scope and with its own
  file's imports: `/api/residents/{residentId}`, no `wildcard-import`.

## HTTP inheritance and generated interfaces

- `src/main/java/com/acme/care/billing/web/InvoiceQueryController.java` with
  `target/generated-sources/openapi/src/main/java/com/acme/care/billing/api/InvoicesApi.java`:
  whether `origin` follows the record's `file` or the file the mapping came from.
  **Chosen:** `file`/`line` = the controller's `@Override` (most-derived declaration), so
  `origin: SOURCE`, `anno: @RequestMapping`, `via: [inherited:InvoicesApi]`, `INFERRED`. No
  record in the fixture has `origin: GENERATED`.
- `src/main/java/com/acme/care/resident/web/ResidentAdmissionController.java`: the class
  implements the missing `ResidentAdmissionsApi` and the in-source constants interface
  `ResidentPaths`. **Chosen:** `<generated:ResidentAdmissionsApi>` records only for the two
  `@Override` methods; `pendingCount` (own mapping, no `@Override`) is a normal `STATIC`
  record. Not exercised: read literally, the rule fires for any controller implementing any
  out-of-source interface (e.g. `InitializingBean`) once `openapi-generator` is in the build.
- `src/main/java/com/acme/care/resident/web/ResidentContactController.java`, `summary`:
  overrides a mapped superclass method without a mapping of its own. **Chosen:** the mapping
  is inherited from `AbstractResidentController`, `file`/`line` = the override, `INFERRED`;
  the missing-interface rule does not apply (the supertype is in the source set).
- `src/main/java/com/acme/care/resident/web/ResidentDocumentController.java`: the method-level
  mappings are its own, only the prefix is inherited. **Chosen:** `INFERRED` with
  `inherited:AbstractResidentController`.

## Other kinds

- `src/main/java/com/acme/care/resident/schedule/OccupancyScheduler.java`, `warmUpRoomCache`:
  `ONE_TIME` has no target rule. **Chosen:** the `initialDelay` formatted as a numeric
  attribute, `2 MINUTES` (and the same in `attrs.initialDelay`).
- `src/main/java/com/acme/care/legacy/soap/ResidentRegistryPortImpl.java`: with
  `endpointInterface`, records use the interface's methods. **Chosen:** `cls` = the
  implementation, `file`/`line` = its overriding methods, `STATIC` (`inherited:` is defined
  for HTTP only). The public `evictCache`, absent from the interface, produces nothing.
- `src/main/java/com/acme/care/legacy/soap/LegacyBillingSoapService.java`: the public
  constructor appears as a method named `<init>` in javac's tree. **Chosen:** constructors are
  not methods; no record.
- `src/main/java/com/acme/care/dailycare/web/DailyCareRoutes.java`: `attrs.routes` counts
  route-builder calls, and the builder entry `route()` is itself a `route` call. **Chosen:**
  `routes: 4` for three routes (`route`, `GET`, `GET`, `POST`).
- `src/main/java/com/acme/care/legacy/messaging/LegacyPharmacyListener.java`: the Rabbit row
  defines class-level records but no `attrs.handlers`. **Chosen:** handlers are not required;
  `@RabbitHandler` methods produce no records.
- `src/main/java/com/acme/care/resident/events/ResidentAuditListener.java`, `onResidentMoved`:
  `classes` is set and the parameter is `Object`. **Chosen:** `classes` wins
  (`ResidentDischargedEvent,ResidentTransferredEvent`).
- `src/main/java/com/acme/care/legacy/web/LegacyBrokenRecordsController.java` (parse failure):
  the regex path's `method` value is not specified. **Chosen:** the name of the method
  declared after the mapping annotation, `listBrokenRecords`; `anno: @GetMapping`,
  `origin: FALLBACK`, `via: [regex]`.
- `target/classes/com/acme/care/billing/web/RetiredInvoiceExportController.java`: a `.java`
  file inside `target/classes`. **Chosen:** skipped; `target/` is build output and `classes`
  is not a generated-source directory.

## Resolutions (2026-10-22)

`plugin/schemas/entrypoint-rules.md` now settles each point above:

1. Status covers `subkind` and `target` only. An unresolvable `attrs` value no longer makes a
   record UNKNOWN (`downloadStatement` → STATIC).
2. `ONE_TIME` targets are the initial delay, formatted like other intervals.
3. `origin` is the origin of the file the record points to.
4. Well-known framework interfaces never trigger the missing-interface rule.
5. `endpointInterface` operations belong to the implementing class and stay STATIC.
   Constructors are not operations.
6. `attrs.routes` counts the starting `route()` call too.
7. A record reached only through `meta:` is STATIC. A wildcard-resolved prefix makes the record INFERRED.
8. Unresolvable path segments are joined like any other segment.
9. `<bindings>` is a fixed INFERRED token.
10. The regex path names the method that follows the annotation.
11. `ApplicationListener<T>` records are STATIC.

Separately, the trailing-slash rule changed while this fixture was being written. A trailing
`/` is now kept, because Spring treats `/a` and `/a/` as different patterns, so
`invoices/` → `/api/billing/admin/invoices/` and `leads/` → `/api/crm/import/leads/`.

## Revised contract (2026-10-22)

Readings chosen for the cases added for the revised rules (LIFECYCLE, WEBSOCKET, GRAPHQL,
GRPC, ACTUATOR, functional routes, webhook tag and the new consumers). Paths are relative to
`fixtures/spring-golden/`.

- `src/main/java/com/acme/care/dailycare/web/DailyCareRoutes.java`: all three routes now have
  string paths, so the bean gives three `router-function` HTTP records and no ROUTER record.
  The `attrs.routes: 4` bullet above no longer applies. ROUTER is now covered by
  `ResidentRouteConfig.spaFallbackRoute` (one `route` call, no path).
- `src/main/java/com/acme/care/resident/events/AbstractAdmissionListener.java`,
  `src/main/java/com/acme/care/shared/events/AbstractAuditingListener.java`,
  `src/main/java/com/acme/care/shared/jobs/AbstractCareHomeJob.java`: unlike HTTP and
  LIFECYCLE, the `ApplicationListener` and `QUARTZ` rules do not say "concrete classes only".
  **Chosen:** an abstract class gets no record of its own; only its concrete subclasses do,
  through `inherited:`. The abstract symbols are in `not-entrypoints.txt`. Read literally,
  the rules would add records for all three abstract classes.
- `src/main/java/com/acme/care/shared/events/ShutdownAuditListener.java`: "the target is
  `<T>`". **Chosen:** `<T>` stands for the variable's own name, so the target is `<E>`, like
  any `<expression text>`. The subclass's type argument (`ContextClosedEvent`) is not
  substituted. The status is UNKNOWN, the worse of INFERRED (`inherited:`) and UNKNOWN.
- `src/main/java/com/acme/care/shared/ops/EventReplayEndpoint.java`: "`<id>` followed by the
  joined method path". **Chosen:** `replay/{eventId}`, with no leading `/`. The class is not an
  HTTP controller (`@RestControllerEndpoint` is not `@RestController`), so its `@PostMapping`
  gives no HTTP record.
- `src/main/java/com/acme/care/dailycare/chat/NurseChatController.java`, `participants`: the
  rule joins the class-level `@MessageMapping` with "the method's" destination. **Chosen:**
  this applies to `@SubscribeMapping` too, as in Spring: `/chat/participants`.
- `src/main/java/com/acme/care/resident/web/VisitBookingController.java`, `slots`:
  `ambiguous-mapping` is not listed as a cause of INFERRED. **Chosen:** STATIC,
  `GET /api/visits/slots`. The second annotation, `@PostMapping("/slots/search")`, produces
  nothing.
- `src/main/java/com/acme/care/shared/scheduling/NightlyMaintenance.java` with
  `src/main/java/com/acme/care/resident/schedule/RoomTurnoverMaintenance.java`: SCHEDULED
  excludes interface methods, and inheritance covers only "implements/extends" triggers.
  **Chosen:** no record for either symbol. Spring's `ScheduledAnnotationBeanPostProcessor`
  also inspects interface methods of a bean, so the contract misses a job that runs.
- `src/main/java/com/acme/care/shared/security/SecurityConfig.java`:
  `csrf(c -> c.ignoringRequestMatchers("/api/integrations/**", …))` is not a `requestMatchers`
  call chained into `permitAll()`. **Chosen:** no `permit-all` from it, so
  `SlackIntegrationController.uninstall` (DELETE, no other signal) stays untagged. Matchers
  inside the `authorizeHttpRequests(auth -> auth…)` lambda count as directly chained.
- `src/main/java/com/acme/care/shared/web/PublicSiteController.java`, `recordCourierStatus`:
  **Chosen:** `permit-all` counts toward the score of 3 when another signal fires.
  `path:weak` (`deliveries`, 1) + `permit-all` (2) gives WEBHOOK MEDIUM, not
  UNAUTHENTICATED_WRITE.
- `src/main/java/com/acme/care/resident/web/NotificationPreferencesController.java`:
  **Chosen:** path signals match whole segments, so `notification-preferences` does not
  match `notifications?`. Only `name` fires (2), so neither the PUT (below 3) nor the GET (no
  `path:strong`, no `webhook` name) gets a verdict.
- `src/main/java/com/acme/care/dailycare/messaging/FallAlertReader.java` (`drainAlerts`) and
  `src/main/java/com/acme/care/dailycare/nursecall/CallBellEscalator.java` (`start`): one
  method triggers rules of two kinds. **Chosen:** each rule emits its own record (SCHEDULED +
  MESSAGING; LIFECYCLE + SCHEDULED `PROGRAMMATIC`).
- `src/main/java/com/acme/care/shared/audit/AuditLogFlusher.java`: **Chosen:** SHUTDOWN_HOOK
  names the method that contains the `addShutdownHook` call (`installShutdownHook`), not the
  `@PostConstruct` method that calls it.
- Not exercised: the missing-interface exemptions cover `InitializingBean` (see
  `src/main/java/com/acme/care/resident/web/CareLevelController.java`, which gets no
  `<generated:…>` record), but not `SmartLifecycle`, `ApplicationRunner` or
  `ApplicationListener`. With `openapi-generator` in the build, a controller implementing one
  of those would, read literally, get `<generated:…>` records for its `@Override` lifecycle
  methods. The same applies to `afterPropertiesSet` on a controller that also implements a
  missing generated interface.

## Resolutions of the second round (2026-10-22)

- **Interface default `@Scheduled`.** Spring schedules such a method on every implementing bean,
  so the rule now projects it onto each concrete implementation as `INFERRED`
  (`RoomTurnoverMaintenance.runNightly`). The interface itself still gets no record.
- **Missing-interface exemptions.** These now include the lifecycle, runner, listener,
  scheduling, MVC-configuration and `java.util.function` interfaces.
- **`ApplicationListener`, Quartz and abstract bases.** The rules now say "concrete class",
  matching the reading chosen here.
- The other readings above match the scanner and stay as written.
