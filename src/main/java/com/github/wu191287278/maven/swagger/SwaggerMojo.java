package com.github.wu191287278.maven.swagger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wu191287278.maven.swagger.doc.SwaggerDocs;
import com.github.wu191287278.maven.swagger.doc.visitor.ResolveSwaggerType;
import com.google.common.collect.ImmutableMap;
import io.swagger.models.Model;
import io.swagger.models.Path;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(phase = LifecyclePhase.COMPILE)
public class SwaggerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(name = "title", defaultValue = "Api Documentation")
    private String title;

    @Parameter(name = "version", defaultValue = "1.0.0")
    private String version;

    @Parameter(name = "description", defaultValue = "")
    private String description;

    @Parameter(name = "schema", defaultValue = "http")
    private String schema;

    @Parameter(name = "host", defaultValue = "localhost")
    private String host;

    @Parameter(name = "basePath", defaultValue = "/")
    private String basePath;

    @Parameter(name = "camel", defaultValue = "true")
    private Boolean camel;

    @Parameter(name = "combineProject", defaultValue = "")
    private String combineProject;

    @Parameter(name = "timeFormat", defaultValue = "13:11:43")
    public String timeFormat;

    @Parameter(name = "dateFormat", defaultValue = "2018-09-10")
    public String dateFormat;

    @Parameter(name = "datetimeFormat", defaultValue = "2018-09-10T13:11:43Z")
    public String datetimeFormat;

    @Parameter(name = "outputDirectory", defaultValue = "${project.build.outputDirectory}/static")
    private File outputDirectory;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String packaging = project.getPackaging();
        if ("pom".equals(packaging)) {
            return;
        }
        List<String> libs = new ArrayList<>();
        try {
            for (String compileClasspathElement : project.getCompileClasspathElements()) {
                if (compileClasspathElement == null || new File(compileClasspathElement).isDirectory()) {
                    continue;
                }
                libs.add(compileClasspathElement);
            }
        } catch (DependencyResolutionRequiredException e) {
            getLog().error(e);
            return;
        }

        MavenProject parent = project;
        while (parent.hasParent()) {
            if (parent.isExecutionRoot()) {
                break;
            }
            parent = parent.getParent();
        }

        SwaggerDocs swaggerDocs = new SwaggerDocs(getTitle(), getDescription(), getVersion(), getBasePath(), getHost());
        swaggerDocs.setCamel(getCamel());
        ResolveSwaggerType.DATE_FORMAT = getDateFormat();
        ResolveSwaggerType.TIME_FORMAT = getTimeFormat();
        ResolveSwaggerType.DATETIME_FORMAT = getDatetimeFormat();
        Map<String, Swagger> m = swaggerDocs.parse(parent.getBasedir().getAbsolutePath(), null, libs, c -> {
            getLog().info("Parsing " + c);
        });

        File output = getOutputDirectory();
        if (!output.exists()) output.mkdirs();

        List<Map<String, String>> urls = new ArrayList<>();

        for (Map.Entry<String, Swagger> entry : m.entrySet()) {
            String filename = entry.getKey() + ".json";
            write(entry.getValue(), new File(output, filename));
            urls.add(ImmutableMap.of("name", entry.getKey(), "url", "./" + filename));
        }
        writeHtml(urls);
    }

    private void writeHtml(List<Map<String, String>> urls) {
        String html = "";
        File file = new File(getOutputDirectory(), "swagger-ui.html");
        try (InputStream in = SwaggerMojo.class.getClassLoader().getResourceAsStream("META-INF/resources/swagger/swagger-ui.html");
             FileWriter writer = new FileWriter(file)) {
            if (in != null) {
                html = IOUtils.toString(in, StandardCharsets.UTF_8);
                html = String.format(html, "urls: " + new ObjectMapper().writeValueAsString(urls));
                writer.write(html);
                getLog().info("Html output path: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            getLog().error(e);
        }
    }

    private void write(Swagger swagger, File out) {
        ObjectMapper objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try (FileWriter writer = new FileWriter(out)) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(swagger);
            writer.write(json);
            getLog().info("Swagger output path: " + out.getAbsolutePath());
        } catch (IOException e) {
            getLog().error(e.getMessage(), e);
        }
    }

    public String getTitle() {
        return System.getProperty("title", title);
    }

    public String getVersion() {
        return System.getProperty("version", version);
    }

    public String getDescription() {
        return System.getProperty("description", description);
    }

    public String getSchema() {
        return System.getProperty("schema", schema);
    }

    public String getHost() {
        return System.getProperty("host", host);
    }

    public String getBasePath() {
        return System.getProperty("basePath", basePath);
    }

    public Boolean getCamel() {
        String property = System.getProperty("camel", String.valueOf(camel));
        return "true".equals(property);
    }

    public String getCombineProject() {
        return System.getProperty("combineProject", combineProject);
    }

    public String getTimeFormat() {
        return System.getProperty("timeFormat", timeFormat);
    }

    public String getDateFormat() {
        return System.getProperty("dateFormat", dateFormat);
    }

    public String getDatetimeFormat() {
        return System.getProperty("datetimeFormat", datetimeFormat);
    }

    public File getOutputDirectory() {
        String output = System.getProperty("outputDirectory", outputDirectory.getAbsolutePath());
        return new File(output);
    }
}
