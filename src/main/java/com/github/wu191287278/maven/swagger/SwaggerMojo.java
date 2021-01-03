package com.github.wu191287278.maven.swagger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

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

        SwaggerDocs swaggerDocs = new SwaggerDocs(title, description, version, basePath, host);
        swaggerDocs.setCamel(camel);
        ResolveSwaggerType.DATE_FORMAT = dateFormat;
        ResolveSwaggerType.TIME_FORMAT = timeFormat;
        ResolveSwaggerType.DATETIME_FORMAT = datetimeFormat;
        Map<String, Swagger> m = swaggerDocs.parse(parent.getBasedir().getAbsolutePath(), null, libs, c -> {
            getLog().info("Parsing " + c);
        });

        if (!outputDirectory.exists()) outputDirectory.mkdirs();


        List<Map<String, String>> urls = new ArrayList<>();

        Swagger swagger = m.get(project.getArtifactId());
        if (swagger != null) {
            if (StringUtils.isNotBlank(combineProject)) {
                String[] combineProjects = combineProject.split(",");
                Map<String, Path> pathMap = new TreeMap<>();
                Map<String, Model> definitionsMap = new TreeMap<>();
                Map<String, Tag> tags = new TreeMap<>();
                for (String combine : combineProjects) {
                    Swagger combineSwagger = m.get(combine.trim());
                    if (combineSwagger == null) continue;
                    pathMap.putAll(combineSwagger.getPaths());
                    definitionsMap.putAll(combineSwagger.getDefinitions());
                    for (Tag tag : combineSwagger.getTags()) {
                        tags.put(tag.getName(), tag);
                    }
                    getLog().info("Combine " + combine + " swagger");
                }
                pathMap.putAll(swagger.getPaths());
                definitionsMap.putAll(swagger.getDefinitions());
                for (Tag tag : swagger.getTags()) {
                    tags.put(tag.getName(), tag);
                }
                swagger.setPaths(pathMap);
                swagger.setDefinitions(definitionsMap);
                swagger.setTags(new ArrayList<>(tags.values()));
            }
            write(swagger, new File(outputDirectory, "swagger.json"));
            urls.add(ImmutableMap.of("name", project.getArtifactId(), "url", "./swagger.json"));
        } else {
            for (Map.Entry<String, Swagger> entry : m.entrySet()) {
                String filename = entry.getKey() + ".json";
                write(entry.getValue(), new File(outputDirectory, filename));
                urls.add(ImmutableMap.of("name", entry.getKey(), "url", "./" + filename));
            }
        }

        writeHtml(urls);
    }

    private void writeHtml(List<Map<String, String>> urls) {
        String html = "";
        File file = new File(outputDirectory, "swagger-ui.html");
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

}
