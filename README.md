# swagger-springboot-maven-plugin

## Introduction

swagger-springboot-maven-plugin 可以根据 Java docs规范 以及springmvc 注解,jsr 311注解 生成 swagger 文档.

## Requirements

需要安装环境 [Maven](https://maven.apache.org/)
and [Java8](https://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) .


## RestController
```
/**
 * 搜索接口
 */
@RestController
@RequestMapping(value="/search")
public class SearchController {

    /**
     * 搜索用户
     *
     * @param nickname 用户昵称
     * @throws 未找到用户
     * @return 用户列表
     */
    @GetMapping(value = "user")
    public String user(@RequestParam(value = "nickname") String nickname) throws NotFoundException{
        return "user:"+nickname;
    }

}
```


## plugin 依赖方式

```xml
<plugin>
    <groupId>com.github.wu191287278</groupId>
    <artifactId>swagger-springboot-maven-plugin</artifactId>
    <version>1.0</version>
    <configuration>
        <host>localhost</host>
        <basePath>/</basePath>
        <title>测试</title>
        <version>1.0</version>
        <description>这是一个测试项目</description>
        <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
        <combineProject>api</combineProject>
    </configuration>
</plugin>
```


### 生成文档

> 默认会在 classes/static文件夹下生成两个文件 swagger-ui.html,swagger.json

```
mvn swagger-springboot:generate
```

## 命令行方式

```
mvn com.github.wu191287278:swagger-springboot-maven-plugin:1.0:generate \
    -Dhost=localhost\
    -DbasePath=/ \
    -Dtitle=test \
    -Dversion=1.0 \
    -Ddescription=这是一个测试项目 \
    -DcombineProject=api
```

## 访问

```
http://你的地址/swagger-ui.html
```

## 变量说明

|变量|说明|备注|
|---|---|---|
|host|对应swagger host|默认 localhost|
|basePath|对应swagger basePath|默认 /|
|title|对应swagger title|默认 Api Documentation|
|version|对应swagger version|默认 1.0.0|
|description|对应swagger description|默认 无|
|outputDirectory|输出目录|默认classes/static文件夹|
|camel|驼峰模式|true驼峰模式,false 下划线模式|
|combineProject|合并多个项目swagger|项目名称逗号分割|
