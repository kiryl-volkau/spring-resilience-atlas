# What the atlas cannot see

The atlas is complete for annotated Spring code in Java. Outside that, it is incomplete, and
an empty answer is not proof of absence. When a question touches one of the items below, say
that the atlas does not cover it and how to check instead.

| Not in the atlas | Check instead |
|---|---|
| XML-configured beans and listeners: `<task:scheduled>`, `<jms:listener-container>`, `<int:*>` flows, `<bean class="...Controller">`. CXF `<jaxws:endpoint>` and `SimpleUrlHandlerMapping` are only counted in the warnings | grep the `*.xml` files under `src/main/resources` and `WEB-INF` |
| Spring Integration `IntegrationFlow` DSL and service activators (only `@InboundChannelAdapter` is recorded) | grep for `IntegrationFlow` and `@ServiceActivator` |
| Listeners registered in code on a container (`setupMessageListener(lambda)`). Only the container `@Bean` is recorded | open the container bean's method |
| Entry points declared through annotations or base classes from internal libraries | `stats` warnings and `manifest.json` → `unresolvedAnnotations` |
| Plain servlets (`@WebServlet`, `ServletRegistrationBean`), legacy `Controller` interface implementations, `HttpRequestHandler` | grep for those types |
| JMX `@ManagedOperation`, Spring Shell `@ShellMethod`, JobRunr / db-scheduler jobs, file watchers, database triggers | grep for the annotation or API |
| Kotlin precision. `.kt` files are scanned by regex: no composed annotations, no inherited mappings, constants only as string `const val`. The Kotlin `router { }` DSL is not read | open the Kotlin file |
| Groovy precision. `.groovy` files are scanned by regex: no composed annotations, no inherited mappings, constants only as single-line `static final` strings. Triple-quoted annotation values come out `UNKNOWN` | open the Groovy file |
| Whether a recorded entry point is active: `@Profile`, `@ConditionalOn*` (kept in `attrs.conditions`), `${…}` placeholders (kept verbatim), `cron = "-"` (`attrs.disabled`) | read the configuration for the profile in question |
| Effective URLs: targets are as declared. Servlet and context paths are in `manifest.build.basePaths` | prepend the base path |
| Spring Cloud Gateway routes (YAML `spring.cloud.gateway.routes` or `RouteLocator` beans). A warning names the dependency | read the gateway configuration |
| Effects of operations whose code is not parsed: Kotlin, Groovy and regex-fallback records have `analysed: false` in `effects.jsonl` and no signals | open the code |
| Effects deeper than three bean hops, or on objects whose type is not declared in the sources (lambda parameters, Lombok-generated fields, library return values) | follow the path with the `critical-flow-tracer` skill |
| Configuration outside the main `application*` and `bootstrap*` files: environment variables, command-line arguments, a config server, Kubernetes or Helm values, profiles named like `local`, `dev` or `test`. Config rules judge only what those files say | read the deployment's environment and manifests |
| Migrations other than the newest under each migration root, and Liquibase changes written over several YAML lines | open the migration files |
| Which `@Bean` a field gets when several of its type exist and no qualifier or name decides: the timeout and executor checks then say nothing | open the configuration class |
| Send destinations set at run time, through a field-injected `@Value`, a properties object or a method argument: the send's `target` is `UNKNOWN` and no link forms | `flow candidates` lists what did resolve; read the sender for the rest |
| Rows one entry point writes and a job or poller picks up later, and providers known only by a client class name: no candidate link forms | the flow tracer can add such a link with `basis: AGENT` |
