package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Facts about the code that are not entry points but limit what the atlas can claim: a path
 * prefix added in configuration, servlets and endpoints registered in code, Spring Integration use.
 *
 * @param registrationSamples up to three {@code Type.method} or type names, for the warning text
 */
record CodeFacts(boolean pathPrefix, int registeredInCode, List<String> registrationSamples, boolean springIntegrationUsed) {

    private static final Set<String> REGISTRATION_BEANS = Set.of("ServletRegistrationBean", "FilterRegistrationBean",
            "ServletListenerRegistrationBean", "EndpointImpl");
    private static final Set<String> SERVLET_PARENTS = Set.of("HttpServlet", "GenericServlet", "HttpSessionListener",
            "ServletContextListener", "HttpSessionAttributeListener");
    private static final Set<String> SERVLET_ANNOTATIONS = Set.of("WebServlet", "WebFilter", "WebListener");

    static CodeFacts of(Index index) {
        boolean prefix = false;
        int registered = 0;
        List<String> samples = new ArrayList<>();
        boolean integration = false;
        for (SourceFile f : index.files) {
            integration |= f.importsFrom("org.springframework.integration");
        }
        for (TypeDecl t : index.types()) {
            boolean configurer = t.superTypes.stream().map(JavaSourceParser::simpleTypeName)
                    .anyMatch(s -> s.equals("WebMvcConfigurer") || s.equals("WebFluxConfigurer"));
            boolean servletType = t.superTypes.stream().map(JavaSourceParser::simpleTypeName).anyMatch(SERVLET_PARENTS::contains)
                    || t.annos.stream().map(Anno::simpleName).anyMatch(SERVLET_ANNOTATIONS::contains);
            if (servletType && !t.isAbstract) {
                registered++;
                sample(samples, t.simpleName);
            }
            for (MethodDecl m : t.methods) {
                if (configurer && m.calls().containsKey("addPathPrefix")) {
                    prefix = true;
                }
                boolean publishes = m.calls().containsKey("publish")
                        && (m.newTypes().contains("EndpointImpl") || t.file.importsFrom("javax.xml.ws.Endpoint")
                        || t.file.importsFrom("jakarta.xml.ws.Endpoint"));
                if (publishes || m.newTypes().stream().anyMatch(REGISTRATION_BEANS::contains)) {
                    registered++;
                    sample(samples, t.simpleName + "." + m.name());
                }
            }
        }
        return new CodeFacts(prefix, registered, samples, integration);
    }

    private static void sample(List<String> samples, String s) {
        if (samples.size() < 3) {
            samples.add(s);
        }
    }
}
