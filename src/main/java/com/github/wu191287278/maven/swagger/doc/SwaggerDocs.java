package com.github.wu191287278.maven.swagger.doc;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cn.hutool.http.HttpUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.github.wu191287278.maven.swagger.doc.visitor.JavaxRsVisitorAdapter;
import com.github.wu191287278.maven.swagger.doc.visitor.RestVisitorAdapter;
import io.swagger.models.*;
import io.swagger.models.auth.ApiKeyAuthDefinition;
import io.swagger.models.auth.In;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author yu.wu
 */
public class SwaggerDocs {

    private Logger log = LoggerFactory.getLogger(SwaggerDocs.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private String title = "Api Documentation";

    private String description = "";

    private String version = "1.0.0";

    private String basePath = "/";

    private String host = "localhost";

    private Boolean camel = true;

    public SwaggerDocs() {
    }

    public SwaggerDocs(String title, String description, String version, String basePath, String host) {
        this.title = title;
        this.description = description;
        this.version = version;
        this.basePath = basePath;
        this.host = host;
    }

    public void writeUI(String path) {
        File file = new File(path);
        if (file.exists()) return;
        try {
            try (InputStream in = SwaggerDocs.class.getClassLoader().getResourceAsStream("static/swagger-ui.html");
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                if (in == null) return;
                IOUtils.copy(in, out);
            }
        } catch (IOException ignore) {

        }
    }


    public void writeTo(String path, Swagger swagger) {
        File file = new File(path);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(swagger);
            out.write(json);
            out.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Swagger> parse(String sourceDirectory, String basePackage, String excludeBasePackage, List<String> libraries, Consumer<String> consumer) {
        log.info("Parsing " + sourceDirectory);
        List<File> filteredDirectories = getSourceDirectories(sourceDirectory);
        List<File> sourceDirectories = getSourceDirectories(sourceDirectory);
        Map<String, Swagger> swaggerMap = new TreeMap<>();
        for (File filteredDirectory : filteredDirectories) {
            String projectPath = filteredDirectory.getAbsolutePath().replace("src/main/java", "")
                    .replace("src\\main\\java", "");
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            for (File sourceFile : sourceDirectories) {
                typeSolver.add(new JavaParserTypeSolver(sourceFile));
            }

            try {
                typeSolver.add(new ReflectionTypeSolver(false));
                JarTypeSolver jarTypeSolver = null;
                for (String library : libraries) {
                    jarTypeSolver = JarTypeSolver.getJarTypeSolver(library);
                }
                if (jarTypeSolver != null) {
                    typeSolver.add(jarTypeSolver);
                }
            } catch (Exception e) {
                log.warn(e.getMessage());
            }


            final RestVisitorAdapter restVisitorAdapter = new RestVisitorAdapter(consumer)
                    .setCamel(camel)
                    .setBasePackage(basePackage);
            final JavaxRsVisitorAdapter javaxRsVisitorAdapter = new JavaxRsVisitorAdapter();
            Info info = new Info()
                    .title(this.title)
                    .description(this.description)
                    .version(this.version);
            final Swagger swagger = new Swagger()
                    .info(info)
                    .paths(new TreeMap<>())
                    .schemes(Arrays.asList(Scheme.HTTP, Scheme.HTTPS))
                    .host(this.host)
                    .basePath(this.basePath)
                    .securityDefinition("api_key", new ApiKeyAuthDefinition("Authorization", In.HEADER));


            ParserConfiguration parserConfiguration = new ParserConfiguration();
            parserConfiguration.setSymbolResolver(new JavaSymbolSolver(typeSolver));

            SourceRoot sourceRoot = new SourceRoot(Paths.get(filteredDirectory.getAbsolutePath()), parserConfiguration);
            List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParseParallelized();

            for (ParseResult<CompilationUnit> parseResult : parseResults) {
                if (!parseResult.isSuccessful()) {
                    continue;
                }
                Optional<CompilationUnit> result = parseResult.getResult();
                if (!result.isPresent()) {
                    continue;
                }
                try {
                    CompilationUnit r = result.get();
                    if (excludeBasePackage != null && !excludeBasePackage.isEmpty()) {
                        Optional<PackageDeclaration> packageDeclarationOptional = r.getPackageDeclaration();
                        if (packageDeclarationOptional.isPresent()) {
                            String packageName = packageDeclarationOptional.get().getNameAsString();
                            if (packageName != null && packageName.startsWith(excludeBasePackage)) {
                                continue;
                            }
                        }
                    }

                    r.accept(javaxRsVisitorAdapter, swagger);
                    r.accept(restVisitorAdapter, swagger);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            for (Map.Entry<String, Model> entry : javaxRsVisitorAdapter.getModelMap().entrySet()) {
                swagger.model(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Model> entry : restVisitorAdapter.getModelMap().entrySet()) {
                swagger.model(entry.getKey(), entry.getValue());
            }

            Set<String> includeTags = new HashSet<>();
            if (swagger.getPaths() != null && !swagger.getPaths().isEmpty()) {
                String projectName = new File(projectPath).getName();
                swagger.getInfo().title(title);
                swagger.host(host);
                swagger.basePath(basePath);
                swaggerMap.put(projectName, swagger);
                for (Path path : swagger.getPaths().values()) {
                    for (Operation operation : path.getOperations()) {
                        if (operation.getTags() != null) {
                            includeTags.addAll(operation.getTags());
                        }
                        Map<String, List<String>> security = Stream.of("api_key")
                                .collect(Collectors.toMap(s -> s, s -> new ArrayList<>()));
                        operation.setSecurity(Collections.singletonList(security));
                    }
                }
            }

            if (swagger.getTags() != null) {
                Map<String, Tag> m = new TreeMap<>();
                for (Tag tag : swagger.getTags()) {
                    if (includeTags.contains(tag.getName())) {
                        m.put(tag.getName(), tag);
                    }
                }
                swagger.tags(new ArrayList<>(m.values()));
            }
        }

        return swaggerMap;
    }

    private List<File> getSourceDirectories(String sourceDirectory) {
        List<File> files = new ArrayList<>();
        filterSourceDirectory(sourceDirectory, files);
        return files;
    }

    private void filterSourceDirectory(String sourceDirectory, List<File> files) {
        File parentDirectoryFile = new File(sourceDirectory);
        if (!parentDirectoryFile.isDirectory()) return;

        File sourceDirectoryFile = new File(sourceDirectory, "/src/main/java/");

        if (!sourceDirectoryFile.exists()) {
            File[] listFiles = parentDirectoryFile.listFiles();
            if (listFiles != null) {
                for (File file : listFiles) {
                    filterSourceDirectory(file.getAbsolutePath(), files);
                }
            }
        } else {
            files.add(sourceDirectoryFile);
        }

        File targetDirectoryFile = new File(sourceDirectory, "/target/generated-sources/annotations/");

        if (!targetDirectoryFile.exists()) {
            File[] listFiles = targetDirectoryFile.listFiles();
            if (listFiles != null) {
                for (File file : listFiles) {
                    filterSourceDirectory(file.getAbsolutePath(), files);
                }
            }
        } else {
            files.add(targetDirectoryFile);
        }
    }

    public void setCamel(Boolean camel) {
        this.camel = camel;
    }

    private static final Map<String, Model> MODEL_MAP = new ConcurrentHashMap<>();

    public static Map<String, Model> getModelMap() {
        try {
            String mergeModels = System.getProperty("modelPath", "");
            if (StringUtils.isBlank(mergeModels) || !MODEL_MAP.isEmpty()) {
                return MODEL_MAP;
            }
            String[] split = mergeModels.split(",");
            for (String modelPath : split) {
                modelPath = modelPath.trim();
                if (modelPath.startsWith("classpath:")) {
                    modelPath = modelPath.replace("classpath:", "");
                    try (InputStream in = CLASSLOADER.getResourceAsStream(modelPath)) {
                        if (in == null) {
                            continue;
                        }
                        String swaggerFile = IOUtils.toString(in, StandardCharsets.UTF_8);
                        Swagger modelSwagger = new SwaggerParser()
                                .parse(swaggerFile);
                        MODEL_MAP.putAll(modelSwagger.getDefinitions());
                    } catch (IOException e) {
                    }
                }
                if (modelPath.startsWith("http://") || modelPath.startsWith("https://")) {
                    String swaggerFile = HttpUtil.get(modelPath);
                    Swagger modelSwagger = new SwaggerParser()
                            .parse(swaggerFile);
                    MODEL_MAP.putAll(modelSwagger.getDefinitions());
                }
            }
        } catch (Exception e) {
        }
        return MODEL_MAP;
    }

    public static ClassLoader CLASSLOADER = SwaggerDocs.class.getClassLoader();

}


