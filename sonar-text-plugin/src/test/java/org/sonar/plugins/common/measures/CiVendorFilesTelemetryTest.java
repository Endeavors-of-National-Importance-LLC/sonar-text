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

import com.sonarsource.scanner.engine.sensor.test.fixtures.SensorContextTester;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.sonar.plugins.common.TestUtils.inputFile;
import static org.sonar.plugins.common.measures.CiVendorFilesTelemetry.CI_VENDOR_TO_GLOB_PATTERNS;
import static org.sonar.plugins.common.measures.CiVendorFilesTelemetry.CI_VENDOR_TO_REL_FILE_PATHS;

class CiVendorFilesTelemetryTest {

  static Stream<Arguments> shouldReportTelemetryForTheDefaultCases() {
    return CI_VENDOR_TO_REL_FILE_PATHS.entrySet().stream()
      .flatMap(entry -> entry.getValue().stream().map(path -> arguments(path, entry.getKey())));
  }

  @MethodSource
  @ParameterizedTest
  void shouldReportTelemetryForTheDefaultCases(String filePath, String vendorToRaiseTelemetryOn) {
    SensorContextTester sensorContext = SensorContextTester.create(Path.of("."));
    InputFile inputFile = inputFile(Path.of(filePath), "");
    sensorContext.fileSystem().add(inputFile);

    verifyCIVendorTelemetryRaised(sensorContext, Set.of(vendorToRaiseTelemetryOn));
  }

  @MethodSource("shouldReportTelemetryForTheDefaultCases")
  @ParameterizedTest
  void shouldNotReportTelemetryWhenFileNotInRoot(String filePath, String vendorsToRaiseTelemetryOn) {
    SensorContextTester sensorContext = SensorContextTester.create(Path.of("."));
    InputFile inputFile = inputFile(Path.of("foo", filePath), "");
    sensorContext.fileSystem().add(inputFile);

    verifyCIVendorTelemetryRaised(sensorContext, Collections.emptySet());
  }

  static Stream<Arguments> shouldReportTelemetryForGlobCases() {
    return CI_VENDOR_TO_GLOB_PATTERNS.entrySet().stream()
      .flatMap(entry -> entry.getValue().stream().map(pattern -> arguments(pattern, entry.getKey())));
  }

  @MethodSource
  @ParameterizedTest
  void shouldReportTelemetryForGlobCases(String pathPattern, String vendorToRaiseTelemetryOn) {
    SensorContextTester sensorContext = SensorContextTester.create(Path.of("."));
    InputFile inputFile = inputFile(Path.of(concreteFileFor(pathPattern)), "");
    sensorContext.fileSystem().add(inputFile);

    verifyCIVendorTelemetryRaised(sensorContext, Set.of(vendorToRaiseTelemetryOn));
  }

  @MethodSource("shouldReportTelemetryForGlobCases")
  @ParameterizedTest
  void shouldReportTelemetryForGlobCasesAtAnyDepth(String pathPattern, String vendorToRaiseTelemetryOn) {
    SensorContextTester sensorContext = SensorContextTester.create(Path.of("."));
    InputFile inputFile = inputFile(Path.of("foo", "bar", concreteFileFor(pathPattern)), "");
    sensorContext.fileSystem().add(inputFile);

    verifyCIVendorTelemetryRaised(sensorContext, Set.of(vendorToRaiseTelemetryOn));
  }

  @Test
  void shouldReportTelemetryForMultipleVendorsInSameProject() {
    SensorContextTester sensorContext = SensorContextTester.create(Path.of("."));
    sensorContext.fileSystem().add(inputFile(Path.of("containers", "Dockerfile"), ""));
    sensorContext.fileSystem().add(inputFile(Path.of(".gitlab-ci.yml"), ""));
    sensorContext.fileSystem().add(inputFile(Path.of(".circleci", "config.yml"), ""));

    verifyCIVendorTelemetryRaised(sensorContext, Set.of("dockerfile", "gitlab", "circleci"));
  }

  @Test
  void shouldNotDeclareSameVendorInRelativeAndGlobMaps() {
    var allVendors = Stream.concat(CI_VENDOR_TO_REL_FILE_PATHS.keySet().stream(), CI_VENDOR_TO_GLOB_PATTERNS.keySet().stream()).toList();

    assertThat(allVendors).doesNotHaveDuplicates();
  }

  private static String concreteFileFor(String pathPattern) {
    var fileName = pathPattern.substring("**/".length());
    return fileName.startsWith("*") ? "main" + fileName.substring(1) : fileName;
  }

  void verifyCIVendorTelemetryRaised(SensorContext sensorContext, Set<String> vendorsToRaiseTelemetryOn) {
    var sensorTelemetry = spy(new TelemetryReporter(sensorContext));

    CiVendorFilesTelemetry.measureProjectsCIFilesInclusion(sensorContext, sensorTelemetry);

    var allVendors = Stream.concat(CI_VENDOR_TO_REL_FILE_PATHS.keySet().stream(), CI_VENDOR_TO_GLOB_PATTERNS.keySet().stream()).toList();
    for (String vendor : allVendors) {
      if (vendorsToRaiseTelemetryOn.contains(vendor)) {
        verify(sensorTelemetry).addNumericMeasure("civendor_" + vendor, 1);
      } else {
        verify(sensorTelemetry).addNumericMeasure("civendor_" + vendor, 0);
      }
    }
  }

}
