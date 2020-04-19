//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0
//DEPS org.kohsuke:github-api:1.101
//DEPS com.fasterxml.jackson.core:jackson-databind:2.2.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.9
//DEPS com.jayway.jsonpath:json-path:2.4.0

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.*;
import static picocli.CommandLine.*;

@Command(name = "epic maker", mixinStandardHelpOptions = true, version = "epic maker 0.1",
        description = "Epic maker made with jbang")
class epic_maker implements Callable<Integer> {

    @Option(names = {"--token"}, defaultValue = "${GITHUB_TOKEN}",
            description = "Token to use for github (env: $GITHUB_TOKEN)", required = true)
    String githubToken;

    /*
    @Option(names={"--repository"}, defaultValue = "${env:GITHUB_REPOSITORY}",
                        description = "Repository used for the labels", required = true)
    String githubRepository;
    */

    @Option(names = {"--eventpath"}, defaultValue = "${GITHUB_EVENT_PATH}",
            description = "Path to read webhook event data", required = true)
    File githubEventpath;


    // @Option(names={"--config"}, defaultValue = "${CONFIG:-.github/autoissuelabeler.yml}")
    // String config;

    @Option(names = {"--noop"}, defaultValue = "false")
    boolean noop;

    public static void main(String... args) {
        int exitCode = new CommandLine(new epic_maker()).execute(args);
        exit(exitCode);
    }

    @Command
    public int init() {
        return ExitCode.OK;
    }

    @Override
    public Integer call() throws Exception {

        setupJson();

        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();

        var data = loadEventData();

        String reponame = data.read("$.repository.name");
        String owner = data.read("$.repository.owner.login");



        String action = data.read("$.action");

        Set validActions = new HashSet() {{
            add("opened");
            add("edited");
            add("labeled");
        }};

        if (validActions.contains(action)) {

            var issue = data.read("$.issue");

            out.println("Found issue");

            if (issue != null) {
                var labels = data.read("$.labels");
                boolean found = false;
                Iterator<JsonNode> iterator = labels.iterator();
                while(iterator.hasNext()) {
                    if (iterator.next().get("name").asText().equals("epic")) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    out.println("Found epic label lets go");
                }

                String body = issue.get("body").asText();

                EpicData epicData = getEpicData(body);

                if(epicData.hasIssues()) {

                    List<String> issues = epicData.getIssues();

                    StringBuffer bf = new StringBuffer();
                    for(var item : issues) {
                        try {
                            var no = Integer.parseInt(item);

                            bf.append(String.format("issue%1$s : issue(number: %1$s) { ...IssueInfo  }\n",item));

                        } catch(NumberFormatException nfe) {
                            //ignore
                        }
                    }


                    String query = String.format("query issues($owner: String!, $name: String!) {\n" +
                            "  repository(owner: $owner, name: $name) {\n" +
                            "    %s" +
                            "  }\n" +
                            "}\n" +
                            "\n" +
                            "fragment IssueInfo on Issue {\n" +
                            "  number\n" +
                            "  title\n" +
                            "  state\n" +
                            "  url\n" +
                            "  assignees(first: 2) {\n" +
                            "    nodes {\n" +
                            "      name\n" +
                            "    }\n" +
                            "  }\n" +
                            "  milestone {\n" +
                            "    title\n" +
                            "    url\n" +
                            "  }\n" +
                            "}\n", bf);

                    Map variables = new HashMap();
                    variables.put("name", reponame);
                    variables.put("owner", owner);

                    Map querydata = new HashMap();
                    querydata.put("query", query);
                    querydata.put("variables", variables);

                    String requestBody = new ObjectMapper().writeValueAsString(querydata);

                    HttpClient client = HttpClient.newHttpClient();

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(java.net.URI.create("https://api.github.com/graphql"))
                            .setHeader("Authorization", "Bearer " + githubToken)
                            .setHeader("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    var issuedata = new ObjectMapper().readValue(response.body(),JsonNode.class);

                    body = body + "\n\n" + generateEpicTable(issuedata.get("data").get("repository"));

                    //out.println(response.body());

                    GHIssue is = github.getRepository(owner + "/" + reponame).getIssue(issue.get("number").asInt());

                    is.setBody(body);

                    out.println("Updated " + is);

                } else {
                    // todo: remove table if no issues

                }



            }

        } else {
            System.out.println("skipped as action: " + action + " not one of " + validActions);
        }
        return 0;
    }

    private void setupJson() {
        Configuration.setDefaults(new Configuration.Defaults() {
            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<com.jayway.jsonpath.Option> options() {
                return EnumSet.noneOf(com.jayway.jsonpath.Option.class);
            }
        });
    }

    private String generateEpicTable(JsonNode repository) {
        StringBuffer buf = new StringBuffer();
        Iterator<JsonNode> iterator = repository.iterator();
        while(iterator.hasNext()) {
            var issue = iterator.next();

            var title = String.format("[#%s](%s) %s", issue.get("number").asText(),
                    issue.get("url").asText(), issue.get("title").asText() );

            var checkbox = "OPEN".equals(issue.get("state").asText()) ? "[ ]" : "[x]";

            buf.append("- " + checkbox + " " + title + "\n");

        }

        return buf.toString();
    }


    EpicData getEpicData(String body) {

        Pattern dataRe = Pattern.compile("(?m)(?s)<!-- EPIC:DATA\\s+(.*)-->");

        Matcher matcher = dataRe.matcher(body);
        if(matcher.find()) {
            out.println("parsing " + matcher.group(1));

            try {
                return new ObjectMapper(new YAMLFactory()).readValue(matcher.group(1), EpicData.class);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private DocumentContext loadEventData() throws java.io.IOException {
        return JsonPath.parse(githubEventpath);
    }

    static class EpicData {
        List<String> issues;

        public List<String> getIssues() {
            return issues;
        }

        public void setIssues(List<String> issues) {
            this.issues = issues;
        }

        @Override
        public String toString() {
            return "EpicData{" +
                    "issues=" + issues +
                    '}';
        }

        public boolean hasIssues() {
            return issues.size()>0;
        }
    }

    }



