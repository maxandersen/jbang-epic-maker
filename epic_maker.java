//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0
//DEPS org.kohsuke:github-api:1.101
//DEPS com.fasterxml.jackson.core:jackson-databind:2.2.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.9
//DEPS com.jayway.jsonpath:json-path:2.4.0
//DEPS org.slf4j:slf4j-nop:1.7.28

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
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jayway.jsonpath.Criteria.where;
import static com.jayway.jsonpath.Filter.filter;
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

    @Option(names = {"--epiclabel"}, defaultValue = "${EPICLABEL:-epic}", description = "Label that has to be on an issue before it is considered a epic")
    String label;

    @Option(names = {"--botname"}, defaultValue = "${BOTNAME}", required=true, description = "User name of the token used to perform updates to issue. Used to avoid infinite loops")
    String botname;


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

        DocumentContext ctx = readEventData();

        // Prevent infinite edit loop
        if (ctx.read("sender.login").equals(botname)) {
            System.out.println("bot made last update - skipping");
            return 0;
        }

        var data = ctx.read("$", JsonNode.class);

        String reponame = ctx.read("$.repository.name");
        String owner = ctx.read("$.repository.owner.login");

        String action = ctx.read("action");

        Set validActions = new HashSet() {{
            add("opened");
            add("edited");
            add("labeled");
        }};

        if (validActions.contains(action)) {

            var issue = data.get("issue");

            List matchinglabels = ctx.read("issue.labels[?(@.name=='" + label + "')].name");

            if (!matchinglabels.isEmpty()) {

                String body = ctx.read("issue.body");

                EpicData epicData = getEpicData(body);

                if (epicData!=null && epicData.hasIssues()) {

                    List<String> issues = epicData.getIssues();

                    StringBuffer bf = new StringBuffer();
                    for (var item : issues) {
                        try {
                            var no = Integer.parseInt(item);

                            bf.append(String.format("issue%1$s : issue(number: %1$s) { ...IssueInfo  }\n", item));

                        } catch (NumberFormatException nfe) {
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

                    var issuedata = JsonPath.parse(response.body());

                    body = stripEpic(body) + "\n\n" + generateEpicTable(issuedata.read("data.repository"));

                } else {
                    body = stripEpic(body);
                }

                if (noop) {
                    System.out.println("noop - would have set body to\n" + body);
                } else {
                    GHIssue is = github.getRepository(owner + "/" + reponame).getIssue(issue.get("number").asInt());
                    is.setBody(body);
                    out.println("Updated " + is.getHtmlUrl());
                }

            } else {
                System.out.println("Ignoring as issue do not have label " + label);
            }
        } else {
            System.out.println("skipped as action: " + action + " not one of " + validActions);
        }
        return 0;
    }

    private String stripEpic(String body) {
        return body.replaceAll("(?m)(?s)<!-- EPIC:START -->.*?<!-- EPIC:END -->", "");
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

    private String generateEpicTable(Map repository) {
        StringBuffer buf = new StringBuffer();
        Iterator<Map> iterator = repository.values().iterator();
        while (iterator.hasNext()) {
            var issue = iterator.next();

            var title = String.format("[#%s](%s) %s", issue.get("number"),
                    issue.get("url"), issue.get("title"));

            var checkbox = "OPEN".equals(issue.get("state")) ? "[ ]" : "[x]";

            buf.append("- " + checkbox + " " + title + "\n");
        }

        String content = "<!-- EPIC:START -->\n" +
                "## Epic items\n" +
                "(this section is auto-generated - manual edits will get lost)\n" +
                "%s" +
                "<!-- EPIC:END -->";

        return String.format(content, buf.toString());
    }


    EpicData getEpicData(String body) {

        Pattern dataRe = Pattern.compile("(?m)(?s)<!-- EPIC:DATA\\s+(.*?)-->");

        Matcher matcher = dataRe.matcher(body);
        if (matcher.find()) {
            //out.println("parsing " + matcher.group(1));
            String data = matcher.group(1);
            try {
                return new ObjectMapper(new YAMLFactory()).readValue(matcher.group(1), EpicData.class);
            } catch (JsonProcessingException e) {
                //could not parse as yaml so parsing as block of numbers

                Pattern pattern = Pattern.compile("(?s)#([0-9]+)");
                matcher = pattern.matcher(data);
                EpicData ed = new EpicData();

                List<String> x = new ArrayList<>();

                while(matcher.find()) {
                    x.add(matcher.group(1));
                }
                ed.setIssues(x);
                return ed;
            }
        }
        return null;
    }

    private JsonNode loadEventData() throws java.io.IOException {
        return new ObjectMapper().readValue(githubEventpath, JsonNode.class);
                 }

    private                                                                        DocumentContext readEventData() throws IOException {
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
            return issues.size() > 0;
        }
    }

}



