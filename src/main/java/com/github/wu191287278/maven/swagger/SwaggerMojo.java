package com.github.wu191287278.maven.swagger;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import cn.hutool.core.util.ZipUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wu191287278.maven.swagger.doc.SwaggerDocs;
import com.github.wu191287278.maven.swagger.doc.visitor.ResolveSwaggerType;
import com.google.common.collect.ImmutableMap;
import io.swagger.models.Model;
import io.swagger.models.Swagger;
import io.swagger.models.properties.Property;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
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

    @Parameter(name = "descriptionFile", defaultValue = "README.md")
    private String descriptionFile;

    @Parameter(name = "schema", defaultValue = "http")
    private String schema;

    @Parameter(name = "host", defaultValue = "localhost")
    private String host;

    @Parameter(name = "basePath", defaultValue = "/")
    private String basePath;

    @Parameter(name = "camel", defaultValue = "true")
    private Boolean camel;

    @Parameter(name = "timeFormat", defaultValue = "13:11:43")
    public String timeFormat;

    @Parameter(name = "dateFormat", defaultValue = "2018-09-10")
    public String dateFormat;

    @Parameter(name = "datetimeFormat", defaultValue = "2018-09-10T13:11:43Z")
    public String datetimeFormat;

    @Parameter(name = "recursionAncestor", defaultValue = "false")
    public Boolean recursionAncestor;

    @Parameter(name = "outputDirectory", defaultValue = "${project.build.outputDirectory}/static")
    private File outputDirectory;

    @Parameter(name = "includeArtifactIds", defaultValue = "")
    private String includeArtifactIds;

    @Parameter(name = "excludeBasePackage", defaultValue = "")
    private String excludeBasePackage;

    @Parameter(name = "basePackage", defaultValue = "")
    private String basePackage;

    @Parameter(name = "skip", defaultValue = "false")
    private String skip;

    @Parameter(name = "mergeModel", defaultValue = "")
    private String mergeModel;

    @Override
    public void execute() {
        if (isSkip()) {
            return;
        }
        Set<String> includeArtifactIdsSet = toSet(getIncludeArtifactIds());

        if (!includeArtifactIdsSet.isEmpty() && !includeArtifactIdsSet.contains(project.getArtifactId().toLowerCase())) {
            return;
        }

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

        MavenProject copyParent = project;
        while (copyParent.hasParent()) {
            if (copyParent.isExecutionRoot()) {
                break;
            }
            copyParent = copyParent.getParent();
        }
        try {
            List<URL> urls = new ArrayList<>();
            for (Artifact artifact : project.getArtifacts()) {
                URL url = artifact.getFile().toURI().toURL();
                urls.add(url);
            }
            ClassLoader prevClassLoader = Thread.currentThread().getContextClassLoader();
            URL[] jarUrlArray = urls.toArray(new URL[]{});
            URLClassLoader cl = URLClassLoader.newInstance(jarUrlArray, prevClassLoader);
            Thread.currentThread().setContextClassLoader(cl);
        } catch (Exception e) {
            getLog().warn(e.getMessage());
        }

        SwaggerDocs swaggerDocs = new SwaggerDocs(getTitle(), getDescription(), getVersion(), getBasePath(), getHost());
        swaggerDocs.setCamel(getCamel());
        ResolveSwaggerType.DATE_FORMAT = getDateFormat();
        ResolveSwaggerType.TIME_FORMAT = getTimeFormat();
        ResolveSwaggerType.DATETIME_FORMAT = getDatetimeFormat();
        ResolveSwaggerType.RECURSION_ANCESTOR = getRecursionAncestor();
        Map<String, Swagger> m = swaggerDocs.parse(copyParent.getBasedir().getAbsolutePath(), getBasePackage(), getExcludeBasePackage(), libs, c -> {
            getLog().info("Parsing " + c);
        });

        File output = getOutputDirectory();
        if (!output.exists()) output.mkdirs();

        List<Map<String, String>> urls = new ArrayList<>();

        if (!includeArtifactIdsSet.isEmpty()) {
            Map<String, Swagger> newM = new HashMap<>();
            for (Map.Entry<String, Swagger> entry : m.entrySet()) {
                if (includeArtifactIdsSet.contains(entry.getKey())) {
                    newM.put(entry.getKey(), entry.getValue());
                }
            }
            m = newM;
        }

        for (Map.Entry<String, Swagger> entry : m.entrySet()) {
            String filename = entry.getKey() + ".json";
            Swagger swagger = entry.getValue();
            mergeModel(swagger);
            write(swagger, new File(output, filename));
            urls.add(ImmutableMap.of("name", entry.getKey(), "url", "./" + filename));
        }
        for (MavenProject collectedProject : copyParent.getCollectedProjects()) {
            if (collectedProject.getName().equals(project.getName())) {
                continue;
            }
            File target = new File(collectedProject.getBasedir(), "target/classes/swagger/" + copyParent.getName());
            target.mkdirs();
            for (Map.Entry<String, Swagger> entry : m.entrySet()) {
                String filename = entry.getKey() + ".json";
                Swagger swagger = entry.getValue();
                write(swagger, new File(target, filename));
                urls.add(ImmutableMap.of("name", entry.getKey(), "url", "./" + filename));
            }
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
        File dist = new File(getOutputDirectory(), "dist.zip");
        try (InputStream in = SwaggerMojo.class.getClassLoader().getResourceAsStream("META-INF/resources/swagger/dist.zip");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dist))) {
            if (in != null) {
                IOUtils.copy(in, out);
            }
        } catch (IOException e) {
            getLog().error(e);
        }
        try {
            File distDir = new File(getOutputDirectory(), "dist");
            distDir.mkdirs();
            ZipUtil.unzip(dist, distDir);
        } catch (Exception e) {
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
        String description = System.getProperty("description", this.description);
        if (StringUtils.isBlank(description)) {
            String descriptionFile = System.getProperty("descriptionFile", this.descriptionFile);
            if (StringUtils.isNotBlank(descriptionFile)) {
                File file = new File(descriptionFile);
                if (file.exists()) {
                    try {
                        description = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                    } catch (IOException e) {

                    }
                }
            }
        }
        return description;
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

    public boolean getRecursionAncestor() {
        String property = System.getProperty("recursionAncestor", String.valueOf(recursionAncestor));
        return "true".equals(property);
    }

    public String getIncludeArtifactIds() {
        return System.getProperty("includeArtifactIds", includeArtifactIds);
    }

    public String getExcludeBasePackage() {
        return System.getProperty("excludeBasePackage", excludeBasePackage);
    }

    public String getBasePackage() {
        return System.getProperty("basePackage", basePackage);
    }

    public void mergeModel(Swagger swagger) {
        try {
            String mergeModels = System.getProperty("modelPath", "");
            if (StringUtils.isBlank(mergeModels)) {
                return;
            }
            String[] split = mergeModels.split(",");
            for (String modelPath : split) {
                modelPath = modelPath.trim();
                if (modelPath.startsWith("classpath:")) {
                    modelPath = modelPath.replace("classpath:", "");
                    try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(modelPath)) {
                        if (in == null) {
                            continue;
                        }
                        String swaggerFile = IOUtils.toString(in, StandardCharsets.UTF_8);
                        Swagger modelSwagger = new SwaggerParser()
                                .parse(swaggerFile);
                        for (Map.Entry<String, Model> entry : swagger.getDefinitions().entrySet()) {
                            String key = entry.getKey();
                            Model replaceModel = entry.getValue();

                            boolean isContinue = false;
                            for (Map.Entry<String, Property> propertyEntry : replaceModel.getProperties().entrySet()) {
                                if (StringUtils.isNotBlank(propertyEntry.getValue().getDescription())) {
                                    isContinue = true;
                                    break;
                                }
                            }
                            if (isContinue) {
                                continue;
                            }
                            if (modelSwagger.getDefinitions() == null) {
                                continue;
                            }

                            Model model = modelSwagger.getDefinitions().get(key);
                            if (model != null) {
                                for (Map.Entry<String, Property> propertyEntry : model.getProperties().entrySet()) {
                                    String description = propertyEntry.getValue().getDescription();
                                    if (StringUtils.isNotBlank(description)) {
                                        replaceModel = model;
                                        break;
                                    }
                                }
                            }
                            swagger.model(entry.getKey(), replaceModel);
                        }
                    } catch (IOException e) {
                        getLog().warn(e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn(e.getMessage());
        }
    }

    public boolean isSkip() {
        String isSkip = System.getProperty("skip", skip);
        return "true".equals(isSkip);
    }

    private Set<String> toSet(String str) {
        Set<String> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            for (String s : str.split(",")) {
                String trim = s.toLowerCase().trim();
                if (trim.isEmpty()) {
                    continue;
                }
                set.add(trim);
            }
        }
        return set;
    }

}
