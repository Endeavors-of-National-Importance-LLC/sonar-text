/*
 * SonarQube Text Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.plugins.common.measures;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.plugins.common.predicates.TextAndSecretsPredicates;

public class CiVendorFilesTelemetry {

  private static final String TELEMETRY_INFIX = "civendor_";

  public static final Map<String, Set<String>> CI_VENDOR_TO_REL_FILE_PATHS = Map.ofEntries(
    Map.entry("travisci", Set.of(".travis.yml")),
    Map.entry("circleci", Set.of(".circleci/config.yml")),
    Map.entry("gitlab", Set.of(".gitlab-ci.yml")),
    Map.entry("appveyor", Set.of("appveyor.yml")),
    Map.entry("bamboo", Set.of("bamboo-specs/bamboo.yml", "bamboo-specs/bamboo.yaml")),
    Map.entry("buildkite", Set.of(".buildkite/pipeline.yml")),
    Map.entry("bitbucketpipelines", Set.of("bitbucket-pipelines.yml")),
    Map.entry("semaphore", Set.of(".semaphore/semaphore.yml")));

  // These vendors' marker files aren't confined to the project root, so they need glob matching.
  public static final Map<String, Set<String>> CI_VENDOR_TO_GLOB_PATTERNS = Map.ofEntries(
    Map.entry("jenkins", Set.of("**/Jenkinsfile")),
    Map.entry("azurepipelines", Set.of("**/azure-pipelines.yml")),
    Map.entry("dockercompose", Set.of("**/docker-compose.yml", "**/docker-compose.yaml", "**/compose.yml", "**/compose.yaml")),
    Map.entry("dockerfile", Set.of("**/Dockerfile")),
    Map.entry("containerfile", Set.of("**/Containerfile")),
    Map.entry("terraform", Set.of("**/*.tf", "**/*.tf.json")),
    // .tofu is OpenTofu-exclusive syntax; plain .tf usage under tofu is indistinguishable from Terraform.
    Map.entry("opentofu", Set.of("**/*.tofu", "**/*.tofu.json")),
    Map.entry("pulumi", Set.of("**/Pulumi.yaml", "**/Pulumi.yml")),
    Map.entry("crossplane", Set.of("**/crossplane.yaml")),
    Map.entry("terragrunt", Set.of("**/terragrunt.hcl", "**/terragrunt.hcl.json")));

  private CiVendorFilesTelemetry() {
    // only static methods
  }

  public static void measureProjectsCIFilesInclusion(SensorContext sensorContext, TelemetryReporter telemetryReporter) {
    if (!TextAndSecretsPredicates.isHiddenFilesAnalysisSupported(sensorContext.runtime())) {
      // Guarantees that telemetry is not calculated in SQ-IDE context since telemetry won't be saved there.
      // Since most ci vendor files are dotfiles/dot-directories, we only calculate this when hidden file analysis is supported
      return;
    }

    var fileSystem = sensorContext.fileSystem();
    var predicates = fileSystem.predicates();

    var detectedVendors = new HashSet<String>();
    var recordingPredicates = Stream.concat(
      CI_VENDOR_TO_REL_FILE_PATHS.entrySet().stream()
        .flatMap(entry -> entry.getValue().stream()
          // Paths are relative to the root
          .<FilePredicate>map(path -> new RecordingPredicate(
            predicates.hasRelativePath(path),
            file -> detectedVendors.add(entry.getKey())))),
      CI_VENDOR_TO_GLOB_PATTERNS.entrySet().stream()
        .flatMap(entry -> entry.getValue().stream()
          .<FilePredicate>map(pattern -> new RecordingPredicate(
            predicates.matchesPathPattern(pattern),
            file -> detectedVendors.add(entry.getKey())))))
      .toList();

    // predicates.or(...) short-circuits while evaluating one input file, which can match only one vendor.
    // The filesystem iteration still visits every matching file, so all vendors are accounted for in the final telemetry.
    var combinedPredicate = predicates.or(recordingPredicates);

    // Iterating triggers RecordingPredicate's recording side effects.
    fileSystem.inputFiles(combinedPredicate).forEach(inputFile -> {
      // no-op: recording already happened inside RecordingPredicate.apply()
    });

    Stream.concat(CI_VENDOR_TO_REL_FILE_PATHS.keySet().stream(), CI_VENDOR_TO_GLOB_PATTERNS.keySet().stream())
      .forEach(vendor -> telemetryReporter.addNumericMeasure(TELEMETRY_INFIX + vendor, detectedVendors.contains(vendor) ? 1 : 0));
  }
}
