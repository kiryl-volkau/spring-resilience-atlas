package studio.volkoff.atlas.effects;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Index;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a message send goes: the queue, topic or binding its destination argument names. Read
 * from a string literal, a constant, a {@code queue("...")} call in a builder lambda, or a field
 * set from a constructor parameter annotated {@code @Value("${key:default}")}, whose key the
 * module's application config resolves. Anything else is unknown, never guessed.
 */
final class Destinations {

    /**
     * @param key    the destination, or null when unknown
     * @param basis  LITERAL, CONSTANT or CONFIG_KEY; null when unknown
     * @param status STATIC when the value is in code or config, INFERRED when only a {@code @Value} default gives it, UNKNOWN otherwise
     * @param via    how it was read, for the link's record
     */
    record Destination(String key, String basis, String status, String via) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("basis", basis);
            m.put("status", status);
            m.put("via", via);
            return m;
        }
    }

    private static final Pattern LITERAL = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"$");
    private static final Pattern QUEUE_CALL = Pattern.compile("\\.(?:queue|topic|destination)\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern NAME = Pattern.compile("^(?:this\\.)?([A-Za-z_$][\\w$]*)$");
    private static final Pattern CONSTANT = Pattern.compile("^(?:[A-Z][\\w$]*\\.)*[A-Z][A-Z0-9_]*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    /** Senders whose first argument names a Spring Cloud Stream binding, not a destination. */
    private static final String STREAM_BRIDGE = "StreamBridge";

    private final Evaluator ev;
    /** (module, key) to the value the module's production config gives, or null. */
    private final BinaryOperator<String> config;

    Destinations(Index index, BinaryOperator<String> config) {
        this.ev = new Evaluator(index);
        this.config = config;
    }

    Destination resolve(TypeDecl declaring, MethodDecl.Site s, String senderType, String module) {
        List<String> args = s.argTexts();
        if (args.isEmpty()) {
            return unknown("no destination argument");
        }
        // RabbitTemplate.convertAndSend(exchange, routingKey, message): both name the destination.
        boolean rabbit = senderType.startsWith("Rabbit") || senderType.equals("AmqpTemplate");
        Destination first = one(declaring, args.get(0), module);
        if (rabbit && s.args() >= 3 && args.size() >= 2) {
            Destination second = one(declaring, args.get(1), module);
            if (first.key() == null || second.key() == null) {
                return unknown("exchange or routing key is not a constant");
            }
            return new Destination(first.key() + "/" + second.key(), first.basis(), weakest(first, second), first.via() + "; " + second.via());
        }
        if (senderType.equals(STREAM_BRIDGE) && first.key() != null) {
            String bound = config.apply(module, "spring.cloud.stream.bindings." + first.key() + ".destination");
            return bound == null ? first : new Destination(bound, "CONFIG_KEY", "STATIC", "binding " + first.key() + " in application config");
        }
        return first;
    }

    private Destination one(TypeDecl declaring, String text, String module) {
        String t = text.strip();
        Matcher lit = LITERAL.matcher(t);
        if (lit.matches()) {
            return new Destination(lit.group(1), "LITERAL", "STATIC", "literal");
        }
        Matcher call = QUEUE_CALL.matcher(t);
        if (call.find()) {
            return new Destination(call.group(1), "LITERAL", "STATIC", "literal in the send builder");
        }
        if (CONSTANT.matcher(t).matches()) {
            Evaluator.Value v = ev.eval(new Expr.Ref(t), Evaluator.Ctx.of(declaring));
            return v.known() && v.v() instanceof String str ? new Destination(str, "CONSTANT", "STATIC", "constant " + t) : unknown("constant " + t + " is not known");
        }
        Matcher name = NAME.matcher(t);
        if (name.matches()) {
            String key = valueKey(declaring, name.group(1));
            if (key != null) {
                return fromKey(key, module);
            }
            return unknown("variable " + name.group(1) + " is set at run time");
        }
        return unknown("expression " + (t.length() > 40 ? t.substring(0, 40) + "…" : t));
    }

    /** The {@code ${...}} text of the {@code @Value} on the constructor parameter that sets the field. */
    private static String valueKey(TypeDecl owner, String field) {
        for (TypeDecl t = owner; t != null; t = t.enclosing) {
            for (MethodDecl m : t.methods) {
                if (!m.isConstructor()) {
                    continue;
                }
                for (MethodDecl.Param p : m.params()) {
                    if (!p.name().equals(field)) {
                        continue;
                    }
                    for (Anno a : p.annos()) {
                        Matcher ph = PLACEHOLDER.matcher(a.text());
                        if (a.simpleName().equals("Value") && ph.find()) {
                            return ph.group();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Resolves a {@code ${key:default}} against the module's config. */
    Destination fromKey(String placeholder, String module) {
        Matcher ph = PLACEHOLDER.matcher(placeholder);
        if (!ph.find()) {
            return unknown("not a placeholder");
        }
        String value = config.apply(module, ph.group(1));
        if (value != null) {
            return new Destination(value, "CONFIG_KEY", "STATIC", ph.group(1) + " in application config");
        }
        if (ph.group(2) != null && !ph.group(2).isEmpty()) {
            return new Destination(ph.group(2), "CONFIG_KEY", "INFERRED", ph.group(1) + " default (no config value)");
        }
        return unknown(ph.group(1) + " is not in the application config");
    }

    private static String weakest(Destination a, Destination b) {
        return a.status().equals("STATIC") && b.status().equals("STATIC") ? "STATIC" : "INFERRED";
    }

    private static Destination unknown(String why) {
        return new Destination(null, null, "UNKNOWN", why);
    }
}
