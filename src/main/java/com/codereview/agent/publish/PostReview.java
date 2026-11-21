package com.codereview.agent.publish;

import com.codereview.agent.model.ReviewModels;
import com.codereview.agent.review.Comment;
import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Reviews a range and posts it as a pull request review.
 *
 * <pre>
 * PostReview &lt;repository&gt; &lt;base&gt; &lt;head&gt; &lt;owner/name&gt; &lt;pull number&gt; [--real] [--dry-run]
 * </pre>
 *
 * <p>Dry run prints the payload and does nothing else. Without it, GITHUB_TOKEN must be set.
 */
public final class PostReview {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private PostReview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("usage: PostReview <repository> <base> <head> <owner/name> "
                    + "<pull number> [--real] [--dry-run]");
            return;
        }
        Path repository = Path.of(args[0]);
        String base = args[1];
        String head = args[2];
        String slug = args[3];
        int pullNumber = Integer.parseInt(args[4]);
        List<String> flags = List.of(args);
        boolean real = flags.contains("--real");
        boolean dryRun = flags.contains("--dry-run") || !flags.contains("--post");

        ReviewPipeline pipeline = real
                ? new ReviewPipeline(ReviewModels.fromEnvironment(),
                        com.codereview.agent.investigate.InvestigateConfig.defaults(),
                        com.codereview.agent.verify.VerifyConfig.defaults())
                : ReviewPipeline.offline();
        ReviewReport report = pipeline.run(repository, base, head);

        Set<String> addedLines = DiffLines.addedLines(repository, base, head);
        List<Comment> inline = DiffLines.inside(report.comments(), addedLines);
        ObjectNode payload = GitHubPayload.review(report, inline);
        if (dryRun) {
            System.out.println("dry run: " + report.comments().size() + " comment(s), "
                    + inline.size() + " of them can be posted inline");
            System.out.println(MAPPER.writeValueAsString(payload));
            Files.createDirectories(Path.of("eval"));
            Files.writeString(Path.of("eval/latest-review-payload.json"),
                    MAPPER.writeValueAsString(payload));
            return;
        }
        String answer = GitHubReviewer.fromEnvironment().post(slug, pullNumber, payload);
        System.out.println("posted " + slug + "#" + pullNumber + ": " + answer.length() + " bytes back");
    }
}
