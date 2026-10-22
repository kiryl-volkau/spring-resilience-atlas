# Entry-point records

`.reliability/atlas/entrypoints.jsonl` holds one JSON object per line, validated by
`entrypoint.schema.json`. This file defines what produces a record and how every field is
computed. The scanner and the golden fixture's expected output both follow it; when they
disagree, either the scanner or this file is wrong.

## Scanned sources

Main sources only. The scanner reads `.java` and `.kt` files and skips:

- source sets other than main: `src/<set>` when the lower-case set name contains `test`, equals
  `it` or `jmh`, or starts with `integration` or `e2e`;
- resource trees: `src/<set>/resources`;
- hidden directories (any name starting with `.`, such as `.git`, `.idea` or `.claude`),
  `node_modules` and `buildSrc`;
- build output: `target/`, `build/` and `out/` outside a `src` tree, unless that directory holds
  a build file of its own, which makes it a module. Generated-source directories inside build
  output (`generated-sources`, `generated`, `generated-src`) are scanned; test variants of those
  directories are not.

`module` is the path of the nearest ancestor directory that holds `pom.xml`, `build.gradle`
or `build.gradle.kts`, relative to the repository root, with `.` for the root.

## Common fields

| Field | Value |
|---|---|
| `id` | `EP-` + the first 8 hex digits of SHA-1 over `kind\|module\|cls#method\|subkind\|target`. Colliding ids are extended to 12 digits |
| `kind` | `HTTP`, `MESSAGING`, `SCHEDULED`, `EVENT`, `LIFECYCLE`, `SOAP`, `JOB`, `WEBSOCKET`, `GRAPHQL`, `GRPC`, `ACTUATOR`, `ROUTER`, `PORTLET` |
| `subkind` | per kind, below |
| `target` | per kind, below. An unresolvable value is rendered as `<expression text>` inside the target |
| `module` | see above |
| `cls` | fully qualified name of the class the record belongs to; nested types use `.` (`com.x.Outer.Inner`) |
| `method` | method name, or `*` for a class-level record |
| `file`, `line` | repository-relative path with `/`, and the 1-based line where the most-derived declaration of the method (or the class, for class-level records) starts |
| `anno` | the triggering annotation as written in the source, such as `@GetMapping` or `@JsonGet`; the signal name for programmatic and supertype-based records |
| `status` | `STATIC`, `INFERRED` or `UNKNOWN`, see [Status](#status) |
| `origin` | the origin of the file that `file` points to: `SOURCE`; `GENERATED` for generated-source directories; `KOTLIN` for `.kt` files; `FALLBACK` for a `.java` file that failed to parse and was scanned by the regex path |
| `via` | how the record was derived, a list of the tags in [Via tags](#via-tags); empty for a plain annotation on the class itself |
| `placeholder` | `true` when the target contains `${` or `#{` |
| `attrs` | extra attributes, per kind |

`symbol` is not stored. Queries print it as `<simple class name>.<method>`, where nested types
keep their outer names: `Outer.Inner.handle`, `Jobs.*`.

**Supertypes.** Wherever a rule below says a class *implements* or *extends* something, every
ancestor counts: supertypes in the source set, followed transitively, and the names of
supertypes outside it. A record found only through an ancestor gets `status: INFERRED` and the
tag `inherited:<Ancestor>`. The ancestor is the source-set type through which the name was
reached.

## HTTP

A **controller** is a concrete class or a record that has `@Controller` or `@RestController`.
The annotation can be on the class or on any supertype in the source set, and can be applied
directly or through a composed annotation.

For each controller:

1. **Methods.** Collect the methods of the controller and of all its supertypes in the source
   set. When the same name and parameter count appear more than once, the most-derived
   declaration wins. When one type declares overloads with the same name and parameter count,
   the erased parameter type names tell the overloads apart.
2. **Method-level mapping.** For each method, take the first `@RequestMapping`-family
   annotation (`@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`,
   `@DeleteMapping`, `@PatchMapping`, or a composed annotation built on them). Search the
   method in the controller first, then the same method in superclasses (nearest first),
   then in interfaces.
   - When no `@RequestMapping`-family annotation is found, `@HttpExchange`-family annotations
     (`@HttpExchange`, `@GetExchange`, `@PostExchange`, `@PutExchange`, `@DeleteExchange`,
     `@PatchExchange`) count the same way, with paths from `value` or `url`. Such records get
     the tag `http-exchange`. This is Spring 6.1+ server-side use of an exchange interface.
   - When one declaration carries more than one mapping annotation, the first one is used, and
     the record gets the tag `ambiguous-mapping`.
3. **Class-level prefix.** Take the first `@RequestMapping` found on the controller, then on its
   superclasses, then on its interfaces. A type-level `@HttpExchange` counts when the mapping
   came from the `@HttpExchange` family.
4. **Records.** Emit one record per pair of class-level path and method-level path. Read `path`,
   `value` or the composed annotation's alias. Join each pair with exactly one `/`. The result
   starts with `/`. A trailing `/` is kept as declared, because Spring treats `/a` and `/a/` as
   different patterns. When neither level gives a path, the target is `/`.
5. **`subkind`.** Use the HTTP methods of the method-level annotation (`@GetMapping` → `GET`,
   `@RequestMapping(method = …)`). When the method level declares none, use the class-level
   `method`. Sort them and join with `,`. When neither level declares any, use `ANY`.
6. **`cls` and `method`.** The record belongs to the concrete controller, even when the
   annotation was inherited.
7. **`attrs`.** Add `consumes`, `produces`, `params` and `headers` when declared. When the
   controller class has `@Profile` or a `@Conditional*` annotation, add `conditions` with that
   annotation text. Add `webhook` when the [webhook rule](#webhook-tag) assigns a verdict.

Special cases:

- **Outbound clients.** Interfaces with `@FeignClient`, or with a type-level `@HttpExchange`
  that no controller implements, are clients and never produce HTTP records. Neither do their
  supertypes.
- **Orphans.** An interface or abstract class that carries mappings but has no concrete
  controller in the source set produces records on itself, with `status: INFERRED` and the
  `orphan` tag.
- **Generated interfaces without a build.** A controller may implement an interface that is not
  in the source set. The rule applies when either the build declares `openapi-generator` or
  `swagger-codegen`, or the interface name ends in `Api`, `Apis`, `Controller`, `Resource`,
  `Endpoint`, `Endpoints`, `Operations` or `Service`.
  - Well-known framework interfaces never count: `*Aware`, `InitializingBean`,
    `DisposableBean`, `ErrorController`, `Serializable`, `Cloneable`, `Comparable`,
    `AutoCloseable`, `Closeable`, `SmartLifecycle`, `Lifecycle`, `ApplicationRunner`,
    `CommandLineRunner`, `ApplicationListener`, `SmartApplicationListener`,
    `WebMvcConfigurer`, `HandlerInterceptor`, `Ordered`, `SchedulingConfigurer`, `Runnable`,
    `Callable`, `Supplier`, `Function`, `Consumer` and `MessageListener`.
  - For each `@Override` method of such a controller that has no mapping of its own and no
    declaration in a supertype in the source set, emit one record: `subkind: ANY`,
    `target: <generated:InterfaceName>`, `status: UNKNOWN` and the tag
    `missing-interface:InterfaceName`.

  These are the generated OpenAPI interfaces of a project that has not been built.

**Functional routes.** A `@Bean` method returning `RouterFunction<…>` produces one HTTP record
per route-builder call `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD` or `OPTIONS` whose first
argument is a string expression. Both builder style (`route().GET("/x", h::x)`) and
`RequestPredicates` style (`route(GET("/x"), h::x)`) count.

- The target is the route path, prefixed by the paths of the enclosing `path("/p", …)` and
  `nest(path("/p"), …)` calls and joined as above.
- `subkind` is the verb and `method` is the bean method.
- `status: INFERRED` and the tag `router-function`.
- `attrs.handler` holds the source text of the handler argument when there is one.

When no route with a path is found, the bean produces one `ROUTER` record instead (see ROUTER).

### Webhook tag

Each HTTP record is scored on these signals:

| Signal | Weight | Fires when |
|---|---|---|
| `path:strong` | 3 | a path segment, ignoring `{…}` variables and case, matches `webhooks?`, `hooks?`, `ipn`, `inbound` or `incoming` |
| `path:weak` | 1 | a segment matches `callbacks?`, `notify`, `notifications?`, `events?`, `push`, `deliver(y\|ies)?` or `listener` |
| `name` | 2 | the controller's or the method's simple name contains, ignoring case, `webhook`, `callback`, `inbound`, `notification`, `hook` or `ipn` |
| `sig-header` | 3 | a `@RequestHeader` value on a parameter, or a string constant of the controller, matches `X-Hub-Signature(-256)?`, `Stripe-Signature`, `X-Slack-Signature`, `X-Twilio-Signature`, `X-Shopify-Hmac-Sha256`, `Paypal-Transmission-Sig`, `Svix-Signature`, `X-GitHub-(Event\|Delivery)`, `X-Atlassian-Webhook-[A-Za-z-]+`, or the generic `X-[A-Za-z-]*-(Signature\|Hmac\|Sig)`, all ignoring case |
| `verify-call` | 3 | see below |
| `raw-body` | 2 | a parameter annotated `@RequestBody` has type `byte[]` or `String` |
| `permit-all` | 2 for a write method, 1 otherwise | the target matches an Ant pattern in a `requestMatchers`, `antMatchers` or `mvcMatchers` call chained directly into `permitAll()`, in a class that references `HttpSecurity`. The match is tried three ways: as declared; with the manifest's servlet path or context path in front of the target; and, for a pattern whose first segment is literal and whose remainder still has a literal segment, with that first segment removed from the pattern, which covers servlet prefixes set in code |

`verify-call` fires when the method or its controller calls `constructEvent`,
`verifySignature`, `validateSignature`, `verifyRequest`, `isValidRequest` or
`isValidSignature`. It also fires when the controller's file imports `com.stripe.net.Webhook`,
`com.svix` or `org.kohsuke.github.GHEventPayload`, and when a type the controller references
in fields or `new` expressions does any of this, provided its name contains `Webhook`,
`Signature`, `Delivery` or `Hook`.

A **write method** is a `subkind` that contains `POST`, `PUT`, `PATCH` or `DELETE`, or is
`ANY`. The verdict is chosen in this order:

1. **`OAUTH_CALLBACK`.** A path segment is `callback`, the method is not a write method, and a
   parameter is named or `@RequestParam`-named `code` or `state`.
2. **`WEBHOOK_MANAGEMENT`.** `path:strong` or a `webhook` name, and either the last path
   segment is one of `secret`, `rotate`, `config`, `settings`, `test`, `ping`, `register`,
   `subscription`, `subscriptions` or `status`, or the method is not a write method and neither
   `sig-header`, `verify-call` nor `raw-body` fired.
3. **`WEBHOOK`.** A write method, a score of at least 3, and at least one signal other than
   `permit-all`. Confidence is `HIGH` from a score of 6, `MEDIUM` below.
4. **`UNAUTHENTICATED_WRITE`.** A write method where only `permit-all` fired.

Records with none of these verdicts get no tag. The tag is stored as
`attrs.webhook = {"verdict": …, "confidence": …, "score": n, "signals": [...]}`, with
`confidence` present only for `WEBHOOK`. It describes the caller and does not change the
status.

## MESSAGING

| Trigger | `subkind` | `target` |
|---|---|---|
| `@KafkaListener` on a method | `KAFKA` | `topics` joined by `,` in declared order; `topicPattern` → `pattern:<value>` |
| `@KafkaListener` on a class | `KAFKA` | as above; `method: *`, `attrs.handlers` = the `@KafkaHandler` method names |
| `@DltHandler` method in a class with a `@KafkaListener` | `KAFKA` | `<dlt>`; `INFERRED`, tag `dlt-handler` |
| `@SqsListener` | `SQS` | `value` or `queueNames` joined by `,` |
| `@RabbitListener` on a method, or on a class with `@RabbitHandler` methods (`method: *`) | `RABBIT` | `queues` joined by `,`; `bindings` → `<bindings>` with `INFERRED` |
| `@JmsListener` | `JMS` | `destination` |
| `@StreamListener` | `STREAM` | `value` or `target` (the binding name) |
| `@Bean` method returning `Consumer<…>` or `Function<…>` when Spring Cloud Stream is present: the build declares a dependency whose name contains `spring-cloud-stream`, `spring-cloud-starter-stream` or `stream-binder`, or the method's file imports from `org.springframework.cloud.stream` | `STREAM` | the method name (the bean name); `INFERRED`, tag `stream-function` |
| `@InboundChannelAdapter` (Spring Integration) | `INTEGRATION` | `value` or `channel`; `INFERRED` |
| `from(uri)` calls in a class that extends `RouteBuilder` or `EndpointRouteBuilder` (Apache Camel) | `CAMEL` | the first argument; `method` is the calling method; `INFERRED`, tag `camel-route` |

For annotation-based listeners, add `id`, `groupId`, `containerFactory`, `factory` and
`concurrency` to `attrs` when declared. Set `attrs.retryableTopic: true` when the method also
has `@RetryableTopic`.

**Programmatic consumers** have `status: INFERRED`, `target: <programmatic>` and the tag
`programmatic:<signal>`:

| Signal | `subkind` | `method` |
|---|---|---|
| The class or an ancestor implements `MessageListener`, `AcknowledgingMessageListener`, `BatchMessageListener`, `BatchAcknowledgingMessageListener`, `ConsumerAwareMessageListener`, `AcknowledgingConsumerAwareMessageListener`, `ChannelAwareMessageListener`, `SessionAwareMessageListener` or `AsyncMessageListener` | from the imports of the file that names the interface: `org.springframework.kafka` → `KAFKA`, `org.springframework.amqp` → `RABBIT`, `javax.jms` / `jakarta.jms` → `JMS`, `io.awspring.cloud.sqs` → `SQS`, `org.springframework.data.redis` → `REDIS`; otherwise `UNKNOWN` | `onMessage` |
| The class or an ancestor implements `ShardRecordProcessor` | `KINESIS` | `processRecords` |
| The method calls `poll`, and the file imports from `org.apache.kafka.clients.consumer` | `KAFKA` | the calling method |
| The method calls `receive`, and the file imports from `reactor.kafka.receiver` | `KAFKA` | the calling method |
| The method calls `receiveMessage`, and the file imports from `software.amazon.awssdk.services.sqs` or `com.amazonaws.services.sqs` | `SQS` | the calling method |
| The method calls `receive` or `receiveMany`, and the file imports from `io.awspring.cloud.sqs.operations` | `SQS` | the calling method |
| The method calls `subscribe`, and the file imports from `com.google.cloud.spring.pubsub` or `com.google.cloud.pubsub` | `PUBSUB` | the calling method |
| `@Bean` method whose return type name ends in `MessageListenerContainer` | `ConcurrentMessageListenerContainer` / `KafkaMessageListenerContainer` → `KAFKA`, `SimpleMessageListenerContainer` / `DirectMessageListenerContainer` → `RABBIT`, `DefaultMessageListenerContainer` → `JMS`, `SqsMessageListenerContainer` → `SQS`; the tag is `container-bean` instead | the bean method |

## SCHEDULED

Every `@Scheduled` on a method of a class produces one record. That includes each element of
a `@Schedules` container and each repetition of the annotation.

A `@Scheduled` on an interface method gives no record on the interface. Spring schedules it
on every bean that implements the interface, so each concrete class in the source set that
implements it gets the record instead, with `status: INFERRED` and the tag
`inherited:<Interface>`. `file`/`line` point to the class's override when it has one. A
class whose override carries its own `@Scheduled` uses that one instead.

**`subkind`**, checked in this order:

1. `CRON` when `cron` is set;
2. `FIXED_RATE` when `fixedRate` or `fixedRateString` is set;
3. `FIXED_DELAY` when `fixedDelay` or `fixedDelayString` is set;
4. `ONE_TIME` when only an initial delay is set.

**`target`:**

- a cron expression verbatim;
- numeric attributes as `<n> <TIMEUNIT>`, where `TIMEUNIT` is the `timeUnit` attribute or
  `MILLISECONDS` by default;
- `…String` attributes verbatim, with ` <TIMEUNIT>` appended only when `timeUnit` is set
  explicitly;
- for `ONE_TIME`, the initial delay, formatted the same way.

**`attrs`:**

- `initialDelay` (formatted the same way), `zone` and `scheduler`;
- `disabled: true` for `cron = "-"`, Spring's disabled marker;
- `schedulerLock` with the lock name when the method also has ShedLock's `@SchedulerLock`.

**Programmatic schedules** have `subkind: PROGRAMMATIC`, `target: <programmatic>`,
`status: INFERRED` and the tag `programmatic:<signal>`:

- a class implementing `SchedulingConfigurer` gives one record with `method: configureTasks`;
- a method that calls `schedule`, `scheduleAtFixedRate`, `scheduleWithFixedDelay` or
  `scheduleWithFixedRate` gives one record per method. This applies when the file imports
  `org.springframework.scheduling.TaskScheduler`, `ThreadPoolTaskScheduler`,
  `java.util.concurrent.ScheduledExecutorService` or `java.util.Timer`, or when the class
  references one of those types. `configureTasks` of a `SchedulingConfigurer` is excluded,
  because it is already recorded.

## EVENT

| Trigger | `subkind` | `target` |
|---|---|---|
| `@EventListener` | `EVENT_LISTENER` | simple names from `classes` or `value`, joined by `,`; otherwise the simple type name of the first parameter, generics stripped; otherwise `<none>` |
| `@TransactionalEventListener` | `TRANSACTIONAL_EVENT_LISTENER` | as above; `attrs.phase` defaults to `AFTER_COMMIT`, plus `fallbackExecution` when declared |
| `@ApplicationModuleListener` (Spring Modulith) | `MODULE_LISTENER` | as above; `attrs.phase: AFTER_COMMIT`, `attrs.async: true`; tag `modulith` |
| A concrete class implementing `ApplicationListener<T>` | `APPLICATION_LISTENER` | the simple name of `T`; `method: onApplicationEvent`; `STATIC` when implemented directly. When `T` is a type variable of an ancestor (a single upper-case letter, optionally followed by digits), the target is `<T>` and the status `UNKNOWN` |
| A concrete class implementing `SmartApplicationListener` or `GenericApplicationListener` | `APPLICATION_LISTENER` | `<supportsEventType>`; `INFERRED` |

For all of them, add `condition` to `attrs` when declared, and set `attrs.async: true` when the
method or its class has `@Async`. Composed annotations count, such as one meta-annotated with
`@EventListener`.

## LIFECYCLE

Startup and shutdown work. These records are what a restart runs and what a deploy can cut
short.

| Trigger | `subkind` | `method` |
|---|---|---|
| The class implements `ApplicationRunner` | `APPLICATION_RUNNER` | `run` |
| The class implements `CommandLineRunner` | `COMMAND_LINE_RUNNER` | `run` |
| A `@Bean` method returning `ApplicationRunner` or `CommandLineRunner` | `APPLICATION_RUNNER` / `COMMAND_LINE_RUNNER` | the bean method; `INFERRED` |
| The class implements `SmartLifecycle` or `Lifecycle` | `LIFECYCLE_START` and `LIFECYCLE_STOP`, two records | `start`, `stop` |
| `@PostConstruct` method | `POST_CONSTRUCT` | the method |
| The class implements `InitializingBean` | `AFTER_PROPERTIES_SET` | `afterPropertiesSet` |
| `@PreDestroy` method | `PRE_DESTROY` | the method |
| The class implements `DisposableBean` | `DESTROY` | `destroy` |
| A method calls `addShutdownHook` | `SHUTDOWN_HOOK` | the calling method; `INFERRED`, tag `programmatic:addShutdownHook` |

The target is the class's simple name, or the bean method's name for `@Bean` runners. Only
concrete classes count. When the class does not declare the method itself, `method` still names
it and `file`/`line` point to the class.

## SOAP

- **`JAX_WS`.** Applies to a concrete class with `@WebService`. Emit one record per public,
  non-static method declared in the class, skipping methods with
  `@WebMethod(exclude = true)`. When `endpointInterface` names an interface in the source set,
  use that interface's methods instead. Those records still belong to the implementing class
  and stay `STATIC`. Constructors are not operations. The target is the `serviceName`
  attribute, otherwise `name`, otherwise the class's simple name.
- **`SPRING_WS`.** Applies to a class with `@Endpoint`, for each method with:
  - `@PayloadRoot`, target `{namespace}localPart`;
  - `@SoapAction`, target `action:<value>`;
  - `@Action`, target `action:<value>`.

## JOB

- **`QUARTZ`.** Applies to a concrete class that implements `Job`, `InterruptableJob` or `StatefulJob`
  from `org.quartz` (the file naming it imports `org.quartz.<Name>` or `org.quartz.*`, or
  writes the qualified name), or that extends `QuartzJobBean`. Either can come through an
  ancestor.
  - `method` is `execute`, or `executeInternal` for `QuartzJobBean`.
  - The target is the class's simple name. The status is `INFERRED`, because the trigger is
    configured elsewhere.
  - `attrs.disallowConcurrent: true` and `attrs.persistJobData: true` reflect
    `@DisallowConcurrentExecution` and `@PersistJobDataAfterExecution` on the class or an
    ancestor.
- **`BATCH`.** Applies to a `@Bean` method returning `Job` in a file that imports
  `org.springframework.batch`. The target is the method name and the status is `INFERRED`.

## WEBSOCKET

- **`STOMP`.** A method with `@MessageMapping` or `@SubscribeMapping` in a controller class.
  - The target is the class-level `@MessageMapping` destination joined with the method's,
    using the path joining above; `<unspecified>` and `UNKNOWN` when neither has a value.
  - When the build declares RSocket (`rsocket`), `@MessageMapping` gives `subkind: RSOCKET`
    instead, and `@ConnectMapping` gives `RSOCKET_CONNECT`.
- **`HANDLER`.** A class that implements `WebSocketHandler`, or extends `TextWebSocketHandler`,
  `BinaryWebSocketHandler` or `AbstractWebSocketHandler`.
  - The target is the class's simple name.
  - `method` is the first of `handleTextMessage`, `handleBinaryMessage` or `handleMessage`
    that the class declares, otherwise `*`.

## GRAPHQL

In a controller class (Spring for GraphQL):

| Annotation | `subkind` | `target` |
|---|---|---|
| `@QueryMapping` | `QUERY` | `Query.<name>` |
| `@MutationMapping` | `MUTATION` | `Mutation.<name>` |
| `@SubscriptionMapping` | `SUBSCRIPTION` | `Subscription.<name>` |
| `@SchemaMapping`, `@BatchMapping` | `FIELD` | `<typeName>.<field>` |

- `<name>` is the `name` or `value` attribute, otherwise the method name.
- For `@SchemaMapping` and `@BatchMapping`, `<field>` is the `field` or `value` attribute,
  otherwise the method name. `typeName` comes from the method annotation, then from a
  class-level `@SchemaMapping`, otherwise it is `<typeName>` and the status `UNKNOWN`.

In a `@DgsComponent` class (Netflix DGS), `@DgsQuery`, `@DgsMutation` and `@DgsSubscription`
map the same way, with the name from `field`. `@DgsData` maps to `FIELD`, with the type from
`parentType` and the name from `field`.

## GRPC

A class annotated `@GrpcService`, or extending a class whose simple name ends in `ImplBase`,
produces one record per public, non-static method that has a parameter whose type starts with
`StreamObserver`.

- `subkind: SERVICE`.
- The target is `<Service>/<method>`. `Service` is the `ImplBase` supertype's simple name
  without `ImplBase`, otherwise the class's simple name.
- `STATIC` with `@GrpcService`, `INFERRED` through the base class alone.

## ACTUATOR

- **Operations.** A class annotated `@Endpoint`, `@WebEndpoint` or `@JmxEndpoint` with an `id`
  attribute, for each method with `@ReadOperation`, `@WriteOperation` or `@DeleteOperation`.
  `subkind` is `READ`, `WRITE` or `DELETE`, and the target is the id.
- **Web controllers.** A class annotated `@RestControllerEndpoint` or `@ControllerEndpoint`,
  for each method with a `@RequestMapping`-family annotation. `subkind` is `WEB` and the
  target is `<id>` followed by the joined method path.

A Spring-WS `@Endpoint` has no `id` and never produces ACTUATOR records.

## ROUTER

- **`ROUTER_FUNCTION`.** A `@Bean` method returning `RouterFunction<…>` in which no route path
  could be extracted (see [functional routes](#http)).
  - The target is the method name and the status is `INFERRED`.
  - `attrs.routes` is the number of route-builder calls in the method (`GET`, `POST`, `PUT`,
    `DELETE`, `PATCH`, `route`, `nest`). The `route()` call that starts the builder counts too,
    so the number is an indication, not an exact route count.

## PORTLET

A controller is a Spring Portlet MVC controller when a type in its hierarchy imports from
`org.springframework.web.portlet`, `javax.portlet` or `jakarta.portlet`, or when its class-level
`@RequestMapping` value is a portlet mode (`VIEW`, `EDIT`, `HELP`, `CONFIG`, `ABOUT`, `PRINT`,
`PREVIEW`, `EDIT_DEFAULTS`, `EDIT_GUEST`). Its handlers produce `PORTLET` records instead of `HTTP`
ones, one per override chain, like HTTP handlers:

| Annotation | `subkind` |
|---|---|
| `@RenderMapping` | `RENDER` |
| `@ActionMapping` | `ACTION` |
| `@ResourceMapping` | `RESOURCE` |
| `@EventMapping` | `EVENT` |
| `@RequestMapping` on a method | `REQUEST` |

- The target is the mode (default `VIEW`), then `:` and the annotation's `value` or `name` when
  set, then ` [params]` when `params` is set: `VIEW:save`, `VIEW [action=delete]`.
- Handlers declared in a supertype are recorded on each concrete controller with the tag
  `inherited:<Type>` and `status: INFERRED`.
- A `RENDER` handler is a read; the other phases count as writes.

## Value resolution

Annotation attributes are evaluated as Java constant expressions:

- literals, and `+` concatenation of strings and numbers;
- `+ - * /` on integers;
- array initialisers, producing one record per element where the rules above say so;
- enum constants (`RequestMethod.GET`, `TimeUnit.MINUTES`), which give the constant name;
- class literals, which give the simple name.

A name is resolved in this order:

1. fields of the current type, its enclosing types and its supertypes, including interface
   constants;
2. single static imports;
3. static wildcard imports;
4. a qualified reference (`Paths.Orders.BASE`, `com.x.Paths.BASE`), resolving the type part
   through nested types, single-type imports, the same package, wildcard imports and fully
   qualified names.

A bare name is never resolved by searching the whole repository. A name that cannot be
resolved this way is `UNKNOWN`.

**Composed annotations** are resolved transitively. An attribute of the composed annotation
overrides the meta-annotation attribute it names through `@AliasFor`, or, failing that, the
meta-annotation attribute with the same name, which adds the `convention-alias` tag. As in
Spring, a `value` attribute without `@AliasFor` never overrides anything. An attribute the
use site does not set takes its `default`. The meta-annotation's own declared attributes, such
as `method = GET`, apply as well.

## Status

The status describes the values that identify the record: `subkind` and `target`. A value
that cannot be evaluated in `attrs`, such as `produces = MediaType.APPLICATION_PDF_VALUE` from
a library, is kept there as text and does not change the status.

- **`STATIC`.** Every value is a literal or was resolved through the lookup above, and the
  triggering annotation or supertype is declared on the record's own class or method. That
  includes an annotation reached only through `meta:` composed annotations.
- **`INFERRED`.** At least one of these holds:
  - the record is derived from an ancestor (`inherited:`), is an orphan, is programmatic, or
    is a stream function, integration adapter, Camel route, functional route, job, bean
    runner or router;
  - it uses a convention alias or was resolved through a wildcard import;
  - it came from the regex path (`KOTLIN`, `FALLBACK`).
- **`UNKNOWN`.** At least one value could not be evaluated.

The worst status wins. Placeholders (`${…}`) and SpEL (`#{…}`) are kept verbatim and do not
lower the status.

## Via tags

| Tag | Meaning |
|---|---|
| `meta:@Name` | Found through the composed annotation `@Name` |
| `inherited:Name` | The mapping, prefix or supertype comes from the ancestor `Name` |
| `orphan` | A mapping on an interface or abstract class with no concrete controller |
| `missing-interface:Name` | The controller implements `Name`, which is not in the source set |
| `http-exchange` | Mapped through an `@HttpExchange`-family annotation |
| `ambiguous-mapping` | The declaration carries more than one mapping annotation |
| `router-function` | A route of a functional `RouterFunction` bean |
| `programmatic:Signal` | A consumer, scheduler or hook wired in code |
| `container-bean` | A `@Bean` listener container |
| `stream-function` | A Spring Cloud Stream functional bean |
| `dlt-handler` | A Kafka dead-letter handler |
| `camel-route` | An Apache Camel route |
| `modulith` | A Spring Modulith module listener |
| `wildcard-import` | A value resolved through a wildcard import |
| `convention-alias` | A composed-annotation attribute mapped by name, not by `@AliasFor` |
| `regex` | Produced by the regex path |

## Manifest warnings

`manifest.json` names everything the atlas may be missing. `stats` and `atlas-bootstrap` print
these warnings:

- Java files that failed to parse and were scanned by regex, and Kotlin files, which are only
  ever scanned by regex.
- HTTP records pointing at missing (generated) interfaces.
- Messaging libraries in the build with no consumer found.
- XML-declared JAX-WS endpoints and `SimpleUrlHandlerMapping` beans.
- **The unresolved-annotation census.** These are annotations on classes or methods that
  resolve neither to the source set nor to an annotation this file handles, and whose simple
  name ends in `Mapping`, `Listener`, `Handler`, `Scheduled`, `Controller`, `Endpoint`,
  `Job`, `Exchange`, `Operation`, `Subscribe` or `Consumer`. The manifest gives the count
  per name and up to three locations. Such annotations usually come from an internal library
  and can hide entry points.
- Controllers whose superclass is outside the source set, which may hide inherited mappings.
- `@Scheduled` records without `@EnableScheduling` anywhere in the source set, and `@Async`
  methods without `@EnableAsync`.
- Frameworks declared in the build that the scanner does not cover: Spring Integration beyond
  `@InboundChannelAdapter`, and XML-configured listeners.

`manifest.build.basePaths` holds `server.servlet.context-path`, `spring.mvc.servlet.path` and
`spring.webflux.base-path` as found in `src/main/resources/application*.{yml,yaml,properties}`,
with placeholders kept verbatim. Targets stay as declared; these paths say how they are
served.
