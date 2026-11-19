package com.codereview.agent;

import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.plan.TriagePlanJson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the triage stage over a revision range and prints the investigation plan.
 *
 * <p>Usage: {@code <repository> <base-revision> <head-revision> [output.json]}. With an output path
 * the plan is written to that file as UTF-8; without one it goes to stdout. The model here is the
 * offline deterministic one, so this needs no credentials and no network.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: <repository> <base-revision> <head-revision> [output.json]");
            System.exit(2);
        }

        Path repository = Path.of(args[0]);
        String base = args[1];
        String head = args[2];

        TriageService service = TriageService.offline();
        TriagePlan plan = service.triage(repository, base, head);
        String json = new TriagePlanJson().render(plan);

        if (args.length >= 4) {
            Files.writeString(Path.of(args[3]), json, StandardCharsets.UTF_8);
            System.err.println("plan written to " + args[3]);
        } else {
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            out.println(json);
        }
    }
}
