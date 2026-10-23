package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.Status;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations.Arg;
import studio.volkoff.atlas.resolve.Annotations.Effective;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Evaluator.Value;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.List;
import java.util.Set;

/** SCHEDULED, JOB and LIFECYCLE records: what time, a scheduler, startup or shutdown sets off. */
final class TriggerExtractor extends ExtractorBase {

    private static final Set<String> SCHEDULE_CALLS = Set.of("schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "scheduleWithFixedRate");
    private static final List<String> SCHEDULER_IMPORTS = List.of("org.springframework.scheduling.TaskScheduler",
            "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler", "java.util.concurrent.ScheduledExecutorService",
            "java.util.Timer");
    private static final Set<String> SCHEDULER_TYPES = Set.of("TaskScheduler", "ThreadPoolTaskScheduler",
            "ScheduledExecutorService", "ScheduledThreadPoolExecutor");
    private static final Set<String> QUARTZ_JOBS = Set.of("Job", "InterruptableJob", "StatefulJob");

    /** Whether any {@code @Scheduled} record was produced, for the missing-{@code @EnableScheduling} warning. */
    int scheduledRecords;

    TriggerExtractor(Context c) {
        super(c);
    }

    void extract(TypeDecl t) {
        scheduled(t);
        jobs(t);
        lifecycle(t);
        beans(t);
    }

    private void scheduled(TypeDecl t) {
        if (t.kind != TypeDecl.Kind.INTERFACE) {
            for (MethodDecl m : t.methods) {
                scheduledMethod(t, t, m, new Decl(t, m), null);
            }
        }
        if (!isConcrete(t)) {
            return;
        }
        for (TypeDecl iface : index.linearize(t)) {
            if (iface.kind != TypeDecl.Kind.INTERFACE) {
                continue;
            }
            for (MethodDecl m : iface.methods) {
                Decl override = findInClassChain(t, m.name());
                if (override != null && an.has(override.method().annos(), Ctx.of(override.owner()), "Scheduled")) {
                    continue;
                }
                scheduledMethod(t, iface, m, override != null ? override : new Decl(iface, m), iface);
            }
        }
        SuperMatch configurer = findSupertype(t, Set.of("SchedulingConfigurer"));
        if (configurer != null) {
            inheritedFrom(record("SCHEDULED", "PROGRAMMATIC", "<programmatic>", t, find(t, "configureTasks"),
                    "SchedulingConfigurer", Status.INFERRED, "configureTasks").via("programmatic:SchedulingConfigurer"), configurer);
        }
        programmaticSchedules(t, configurer);
    }

    /**
     * Records the {@code @Scheduled} triggers declared on {@code m} in {@code declaring}, for the
     * bean {@code owner}; {@code from} is the interface the trigger is inherited from, or null.
     */
    private void scheduledMethod(TypeDecl owner, TypeDecl declaring, MethodDecl m, Decl body, TypeDecl from) {
        Ctx ctx = Ctx.of(declaring);
        String lock = schedulerLock(m, ctx);
        for (Effective e : an.expandAll(m.annos(), ctx)) {
            if (e.base().equals("Scheduled")) {
                schedule(owner, body, e, lock, from);
            } else if (e.base().equals("Schedules")) {
                Arg value = e.attr("value");
                if (value == null) {
                    continue;
                }
                for (Value v : eval(value).elements()) {
                    if (v.v() instanceof Evaluator.AnnoRef ref) {
                        for (Effective inner : an.expand(ref.anno(), ref.ctx())) {
                            if (inner.base().equals("Scheduled")) {
                                schedule(owner, body, new Effective(inner.base(), inner.attrs(), e.useSite(),
                                        mergeVia(e.via(), inner.via()), e.convention() || inner.convention(),
                                        e.wildcard() || inner.wildcard()), lock, from);
                            }
                        }
                    }
                }
            }
        }
    }

    private Decl findInClassChain(TypeDecl t, String methodName) {
        for (TypeDecl x : index.linearize(t)) {
            if (x.kind != TypeDecl.Kind.INTERFACE) {
                Decl d = find(x, methodName);
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    private void programmaticSchedules(TypeDecl t, SuperMatch configurer) {
        boolean usesScheduler = SCHEDULER_IMPORTS.stream().anyMatch(t.file.imports()::contains)
                || t.referencedTypes.stream().anyMatch(SCHEDULER_TYPES::contains);
        if (!usesScheduler) {
            return;
        }
        for (MethodDecl m : t.methods) {
            if (configurer != null && m.name().equals("configureTasks")) {
                continue;
            }
            for (String call : SCHEDULE_CALLS) {
                if (m.calls().containsKey(call)) {
                    record("SCHEDULED", "PROGRAMMATIC", "<programmatic>", t, new Decl(t, m), call, Status.INFERRED)
                            .via("programmatic:" + call);
                    break;
                }
            }
        }
    }

    private String schedulerLock(MethodDecl m, Ctx ctx) {
        Effective lock = an.first(m.annos(), ctx, "SchedulerLock");
        if (lock == null) {
            return null;
        }
        String name = text(lock, "name", "value");
        return name == null ? "" : name;
    }

    private void schedule(TypeDecl t, Decl body, Effective e, String schedulerLock, TypeDecl from) {
        Arg unitArg = e.attr("timeUnit");
        String unit = unitArg == null ? "MILLISECONDS" : Evaluator.enumName(unitArg.expr());
        boolean explicitUnit = unitArg != null;
        String subkind;
        Value target;
        Arg cron = e.attr("cron");
        Value cronValue = cron == null ? null : eval(cron);
        if (cronValue != null && !cronValue.text().isEmpty()) {
            subkind = "CRON";
            target = cronValue;
        } else if (e.attr("fixedRate", "fixedRateString") != null) {
            subkind = "FIXED_RATE";
            target = interval(e.attr("fixedRate"), e.attr("fixedRateString"), unit, explicitUnit);
        } else if (e.attr("fixedDelay", "fixedDelayString") != null) {
            subkind = "FIXED_DELAY";
            target = interval(e.attr("fixedDelay"), e.attr("fixedDelayString"), unit, explicitUnit);
        } else if (e.attr("initialDelay", "initialDelayString") != null) {
            subkind = "ONE_TIME";
            target = interval(e.attr("initialDelay"), e.attr("initialDelayString"), unit, explicitUnit);
        } else {
            subkind = "ONE_TIME";
            target = Value.unknown("unspecified");
        }
        EntryPoint ep = record("SCHEDULED", subkind, target.text(), t, body, "@" + e.useSite().name(),
                target.status());
        if (from != null) {
            ep.via("inherited:" + from.simpleName).atLeast(Status.INFERRED);
        }
        scheduledRecords++;
        target.via().forEach(ep::via);
        applyEffective(ep, e);
        if (!subkind.equals("ONE_TIME") && e.attr("initialDelay", "initialDelayString") != null) {
            ep.attr("initialDelay", interval(e.attr("initialDelay"), e.attr("initialDelayString"), unit, explicitUnit).text());
        }
        for (String a : List.of("zone", "scheduler")) {
            Arg arg = e.attr(a);
            if (arg != null) {
                ep.attr(a, eval(arg).text());
            }
        }
        if (subkind.equals("CRON") && "-".equals(target.text())) {
            ep.attr("disabled", true);
        }
        if (schedulerLock != null) {
            ep.attr("schedulerLock", schedulerLock.isEmpty() ? true : schedulerLock);
        }
    }

    private Value interval(Arg numeric, Arg string, String unit, boolean explicitUnit) {
        if (numeric != null) {
            Value v = eval(numeric);
            return new Value(v.v(), v.text() + " " + unit, v.status(), v.via());
        }
        Value v = eval(string);
        return explicitUnit ? new Value(v.v(), v.text() + " " + unit, v.status(), v.via()) : v;
    }

    private void jobs(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        SuperMatch quartz = quartzJob(t);
        String method = null;
        String signal = null;
        if (quartz != null) {
            method = "execute";
            signal = quartz.simple();
        } else {
            quartz = findSupertype(t, Set.of("QuartzJobBean"));
            if (quartz != null) {
                method = "executeInternal";
                signal = "QuartzJobBean";
            }
        }
        if (quartz == null) {
            return;
        }
        EntryPoint ep = inheritedFrom(record("JOB", "QUARTZ", t.simpleName, t, find(t, method), signal, Status.INFERRED,
                method), quartz);
        if (hierarchyAnno(t, "DisallowConcurrentExecution")) {
            ep.attr("disallowConcurrent", true);
        }
        if (hierarchyAnno(t, "PersistJobDataAfterExecution")) {
            ep.attr("persistJobData", true);
        }
    }

    /** A Quartz job interface on the type or an ancestor, confirmed by the naming file's imports. */
    private SuperMatch quartzJob(TypeDecl t) {
        for (TypeDecl x : index.linearize(t)) {
            SourceFile f = x.file;
            for (String s : x.superTypes) {
                String raw = s.trim();
                String simple = JavaSourceParser.simpleTypeName(raw);
                if (!QUARTZ_JOBS.contains(simple)) {
                    continue;
                }
                boolean quartz = raw.startsWith("org.quartz.") || f.imports().contains("org.quartz." + simple)
                        || f.wildcardImports().contains("org.quartz");
                if (quartz) {
                    return new SuperMatch(x, raw, simple, x != t);
                }
            }
        }
        return null;
    }

    private boolean hierarchyAnno(TypeDecl t, String simpleName) {
        for (TypeDecl x : index.linearize(t)) {
            if (hasAnno(x.annos, simpleName)) {
                return true;
            }
        }
        return false;
    }

    private void lifecycle(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        lifecycleSupertype(t, "ApplicationRunner", "APPLICATION_RUNNER", "run");
        lifecycleSupertype(t, "CommandLineRunner", "COMMAND_LINE_RUNNER", "run");
        SuperMatch lifecycle = findSupertype(t, Set.of("SmartLifecycle", "Lifecycle"));
        if (lifecycle != null) {
            for (String[] phase : new String[][] {{"LIFECYCLE_START", "start"}, {"LIFECYCLE_STOP", "stop"}}) {
                inheritedFrom(record("LIFECYCLE", phase[0], t.simpleName, t, find(t, phase[1]), lifecycle.simple(),
                        Status.STATIC, phase[1]), lifecycle);
            }
        }
        lifecycleSupertype(t, "InitializingBean", "AFTER_PROPERTIES_SET", "afterPropertiesSet");
        lifecycleSupertype(t, "DisposableBean", "DESTROY", "destroy");
        for (MethodDecl m : t.methods) {
            for (Anno a : m.annos()) {
                String subkind = switch (a.simpleName()) {
                    case "PostConstruct" -> "POST_CONSTRUCT";
                    case "PreDestroy" -> "PRE_DESTROY";
                    default -> null;
                };
                if (subkind != null) {
                    record("LIFECYCLE", subkind, t.simpleName, t, new Decl(t, m), "@" + a.name(), Status.STATIC);
                }
            }
            if (m.calls().containsKey("addShutdownHook")) {
                record("LIFECYCLE", "SHUTDOWN_HOOK", t.simpleName, t, new Decl(t, m), "addShutdownHook", Status.INFERRED)
                        .via("programmatic:addShutdownHook");
            }
        }
    }

    private void lifecycleSupertype(TypeDecl t, String name, String subkind, String method) {
        SuperMatch m = findSupertype(t, Set.of(name));
        if (m != null) {
            inheritedFrom(record("LIFECYCLE", subkind, t.simpleName, t, find(t, method), name, Status.STATIC, method), m);
        }
    }

    private void beans(TypeDecl t) {
        Ctx ctx = Ctx.of(t);
        for (MethodDecl m : t.methods) {
            if (m.returnType() == null || !an.has(m.annos(), ctx, "Bean")) {
                continue;
            }
            String type = JavaSourceParser.simpleTypeName(m.returnType());
            Decl d = new Decl(t, m);
            if (type.equals("Job") && t.file.importsFrom("org.springframework.batch")) {
                record("JOB", "BATCH", m.name(), t, d, "@Bean", Status.INFERRED);
            } else if (type.equals("ApplicationRunner")) {
                record("LIFECYCLE", "APPLICATION_RUNNER", m.name(), t, d, "@Bean", Status.INFERRED);
            } else if (type.equals("CommandLineRunner")) {
                record("LIFECYCLE", "COMMAND_LINE_RUNNER", m.name(), t, d, "@Bean", Status.INFERRED);
            }
        }
    }
}
