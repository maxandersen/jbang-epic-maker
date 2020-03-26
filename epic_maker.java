//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0
//DEPS org.kohsuke:github-api:1.101
//DEPS com.fasterxml.jackson.core:jackson-databind:2.2.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.9
//DEPS org.glassfish:jakarta.el:3.0.3

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.codec.language.bm.Rule;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import javax.el.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.System.*;
import static picocli.CommandLine.*;

@Command(name = "issue_labeler", mixinStandardHelpOptions = true, version = "issue_labeler 0.1",
        description = "issue_labeler made with jbang")
class issue_labeler implements Callable<Integer> {

    @Option(names={"--token"}, defaultValue = "${GITHUB_TOKEN}",
                        description = "Token to use for github (env: $GITHUB_TOKEN)", required = true)
    String githubToken;

    /*
    @Option(names={"--repository"}, defaultValue = "${env:GITHUB_REPOSITORY}",
                        description = "Repository used for the labels", required = true)
    String githubRepository;
    */

    @Option(names={"--eventpath"}, defaultValue = "${GITHUB_EVENT_PATH}",
            description = "Path to read webhook event data", required = true)
    File githubEventpath;


   // @Option(names={"--config"}, defaultValue = "${CONFIG:-.github/autoissuelabeler.yml}")
   // String config;

    @Option(names={"--noop"}, defaultValue = "false")
    boolean noop;

    public static void main(String... args) {
        int exitCode = new CommandLine(new issue_labeler()).execute(args);
        exit(exitCode);
    }

    @Command
    public int init() {
        return ExitCode.OK;
    }

    @Override
    public Integer call() throws Exception {

        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();

        var data = loadEventData();

        String action = data.get("action").asText();

        Set validActions = new HashSet() {{
            add("opened");
            add("edited");
            add("labeled");
        }};

        if(validActions.contains(action)) {

            var issue = data.get("issue");

            if(issue!=null) {
                var labels = issue.findValuesAsText("labels");

                if(labels.contains("epic")) {
                    System.out.println("Yay!");
                }

                System.out.println(labels);

            }

        } else {
            System.out.println("skipped as action: " + action + " not one of " + validActions);
        }


/*        if(data.has("pull_request")) {
            System.out.println("Ignoring pull request");
            return ExitCode.OK;
        }

        if(data.get("action")==null || !"opened".equals(data.get("action").asText())) {
            System.out.println("Ignoring this action - only allow opened action");
        }

        Optional<String> title = Optional.ofNullable(data.get("issue").get("title")).map(JsonNode::asText);
        Optional<String> description = Optional.ofNullable(data.get("issue").get("description")).map(JsonNode::asText);
        Optional<String> repository_id = Optional.ofNullable(data.get("repository").get("id")).map(JsonNode::asText);
        Optional<String> issue_number = Optional.ofNullable(data.get("issue").get("number")).map(JsonNode::asText);
        Optional<String> issue_url = Optional.ofNullable(data.get("issue").get("html_url")).map(JsonNode::asText);


        Set<String> labels = new HashSet<>();
        Set<String> comments = new HashSet<>();
        for(Rule rule:rules) {
            if(rule.matches(title.orElse(""), description.orElse(""))) {
                labels.addAll(rule.getLabels());
                if(rule.getAddcomment()!=null) {
                    comments.add(rule.getAddcomment());
                }
            }
        }

        if(labels.isEmpty()) {
            System.out.println("No labels to apply.");
        } else {
            System.out.printf("#%s %s:%s {%s}\n", issue_number.orElse("N/A"), title.orElse("N/A"), labels, comments);
            if(noop) {
                System.out.println("noop - not adding labels nor comments");
            } else {
                GHIssue issue = github.getRepositoryById(repository_id.orElseThrow())
                                    .getIssue(Integer.parseInt(issue_number.orElseThrow()));
                issue.addLabels(labels.toArray(new String[0]));

                if(!comments.isEmpty())
                    issue.comment(comments.stream().collect(Collectors.joining("\n")));
                }

            System.out.println(issue_url.get());
        } */
        return 0;
    }

    private JsonNode loadEventData() throws java.io.IOException {
        return new ObjectMapper().readValue(githubEventpath, JsonNode.class);
    }

    }



