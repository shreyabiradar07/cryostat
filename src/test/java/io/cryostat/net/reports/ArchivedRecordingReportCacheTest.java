/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net.reports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.recordings.RecordingArchiveHelper;
import io.cryostat.recordings.RecordingNotFoundException;
import io.cryostat.rules.ArchivePathException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchivedRecordingReportCacheTest {

    ArchivedRecordingReportCache cache;
    String sourceTarget;
    String recordingName;

    @Mock CompletableFuture<Path> pathFuture;
    @Mock Path destinationFile;
    @Mock FileSystem fs;
    @Mock SubprocessReportGenerator subprocessReportGenerator;
    @Mock Logger logger;
    @Mock RecordingArchiveHelper recordingArchiveHelper;

    @BeforeEach
    void setup() {
        this.cache =
                new ArchivedRecordingReportCache(
                        fs, () -> subprocessReportGenerator, recordingArchiveHelper, 30, logger);
        this.sourceTarget = "service:jmx:rmi://localhost:9091/jndi/rmi://fooHost:9091/jmxrmi";
        this.recordingName = "foo";
    }

    @Test
    void getShouldThrowIfNoCacheAndNoRecording() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future1);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(false);
        Mockito.when(fs.deleteIfExists(Mockito.any(Path.class))).thenReturn(false);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future2.get())
                .thenThrow(
                        new ExecutionException(
                                new RecordingNotFoundException(
                                        RecordingArchiveHelper.ARCHIVES, recordingName)));

        Mockito.when(
                        recordingArchiveHelper.getRecordingPath(
                                Mockito.nullable(String.class), Mockito.eq(recordingName)))
                .thenReturn(future2);

        ExecutionException ee =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> cache.get(sourceTarget, recordingName, "", true).get());

        MatcherAssert.assertThat(
                ExceptionUtils.getRootCause(ee),
                Matchers.instanceOf(RecordingNotFoundException.class));
        Mockito.verify(fs, Mockito.atLeastOnce()).isReadable(destinationFile);
        Mockito.verify(fs, Mockito.atLeastOnce()).deleteIfExists(destinationFile);
    }

    @Test
    void getShouldGenerateAndCacheReport() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future1);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(false);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Path recording = Mockito.mock(Path.class);
        Mockito.when(future2.get()).thenReturn(recording);

        Mockito.when(
                        recordingArchiveHelper.getRecordingPath(
                                Mockito.nullable(String.class), Mockito.anyString()))
                .thenReturn(future2);

        Mockito.when(pathFuture.get(Mockito.anyLong(), Mockito.any())).thenReturn(destinationFile);

        Mockito.when(
                        subprocessReportGenerator.exec(
                                Mockito.any(Path.class),
                                Mockito.any(Path.class),
                                Mockito.anyString(),
                                Mockito.anyBoolean()))
                .thenReturn(pathFuture);

        Future<Path> res = cache.get(sourceTarget, recordingName, "", true);

        MatcherAssert.assertThat(res.get(), Matchers.sameInstance(destinationFile));
        Mockito.verify(subprocessReportGenerator).exec(recording, destinationFile, "", true);
        Mockito.verify(fs, Mockito.atLeastOnce()).isReadable(destinationFile);
    }

    @Test
    void getShouldGenerateAndCacheReportFiltered() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "someFilter", true))
                .thenReturn(future1);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(false);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Path recording = Mockito.mock(Path.class);
        Mockito.when(future2.get()).thenReturn(recording);

        Mockito.when(
                        recordingArchiveHelper.getRecordingPath(
                                Mockito.nullable(String.class), Mockito.anyString()))
                .thenReturn(future2);

        Mockito.when(pathFuture.get(Mockito.anyLong(), Mockito.any())).thenReturn(destinationFile);

        Mockito.when(
                        subprocessReportGenerator.exec(
                                Mockito.any(Path.class),
                                Mockito.any(Path.class),
                                Mockito.anyString(),
                                Mockito.anyBoolean()))
                .thenReturn(pathFuture);

        Future<Path> res = cache.get(sourceTarget, recordingName, "someFilter", true);

        MatcherAssert.assertThat(res.get(), Matchers.sameInstance(destinationFile));
        Mockito.verify(subprocessReportGenerator)
                .exec(recording, destinationFile, "someFilter", true);
        Mockito.verify(fs, Mockito.atLeastOnce()).isReadable(destinationFile);
    }

    @Test
    void getShouldGenerateReportUnformatted() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "someFilter", false))
                .thenReturn(future1);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(false);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Path recording = Mockito.mock(Path.class);
        Mockito.when(future2.get()).thenReturn(recording);

        Mockito.when(
                        recordingArchiveHelper.getRecordingPath(
                                Mockito.nullable(String.class), Mockito.anyString()))
                .thenReturn(future2);

        Mockito.when(pathFuture.get(Mockito.anyLong(), Mockito.any())).thenReturn(destinationFile);

        Mockito.when(
                        subprocessReportGenerator.exec(
                                Mockito.any(Path.class),
                                Mockito.any(Path.class),
                                Mockito.anyString(),
                                Mockito.anyBoolean()))
                .thenReturn(pathFuture);

        Future<Path> res = cache.get(sourceTarget, recordingName, "someFilter", false);

        MatcherAssert.assertThat(res.get(), Matchers.sameInstance(destinationFile));
        Mockito.verify(subprocessReportGenerator)
                .exec(recording, destinationFile, "someFilter", false);
        Mockito.verify(fs, Mockito.atLeastOnce()).isReadable(destinationFile);
    }

    @Test
    void getShouldReturnCachedFileIfAvailable() throws Exception {
        CompletableFuture<Path> future = Mockito.mock(CompletableFuture.class);
        Mockito.when(future.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(true);
        Mockito.when(fs.isRegularFile(Mockito.any(Path.class))).thenReturn(true);

        Future<Path> res = cache.get(sourceTarget, recordingName, "", true);

        MatcherAssert.assertThat(res.get(), Matchers.sameInstance(destinationFile));
        Mockito.verifyNoInteractions(subprocessReportGenerator);
        Mockito.verify(fs).isReadable(destinationFile);
        Mockito.verify(fs).isRegularFile(destinationFile);
    }

    @Test
    void shouldThrowErrorIfReportGenerationFails() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future1);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(false);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Path recording = Mockito.mock(Path.class);
        Mockito.when(future2.get()).thenReturn(recording);

        Mockito.when(
                        recordingArchiveHelper.getRecordingPath(
                                Mockito.nullable(String.class), Mockito.anyString()))
                .thenReturn(future2);

        Mockito.when(
                        subprocessReportGenerator.exec(
                                Mockito.any(Path.class),
                                Mockito.any(Path.class),
                                Mockito.anyString(),
                                Mockito.anyBoolean()))
                .thenThrow(
                        new CompletionException(
                                new SubprocessReportGenerator.SubprocessReportGenerationException(
                                        SubprocessReportGenerator.ExitStatus.OUT_OF_MEMORY)));

        ExecutionException ee =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> cache.get(sourceTarget, "foo", "", true).get());

        MatcherAssert.assertThat(
                ExceptionUtils.getRootCause(ee),
                Matchers.instanceOf(
                        SubprocessReportGenerator.SubprocessReportGenerationException.class));
        Mockito.verify(fs, Mockito.atLeastOnce()).isReadable(destinationFile);
    }

    @Test
    void shouldThrowIfCachedPathResolutionFails() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenThrow(new CompletionException(new IOException()));

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future1);

        Mockito.when(fs.deleteIfExists(Mockito.nullable(Path.class))).thenReturn(false);

        ExecutionException ee =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> cache.get(sourceTarget, "foo", "", true).get());
        MatcherAssert.assertThat(
                ExceptionUtils.getRootCause(ee), Matchers.instanceOf(IOException.class));

        Mockito.verify(fs, Mockito.atLeastOnce()).deleteIfExists(null);
    }

    @Test
    void shouldThrowIfRecordingPathResolutionFails() throws Exception {
        CompletableFuture<Path> future1 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future1.get()).thenReturn(destinationFile);

        Mockito.when(
                        recordingArchiveHelper.getCachedReportPath(
                                sourceTarget, recordingName, "", true))
                .thenReturn(future1);

        CompletableFuture<Path> future2 = Mockito.mock(CompletableFuture.class);
        Mockito.when(future2.get())
                .thenThrow(
                        new CompletionException(
                                new ArchivePathException("/path/to/foo", "does not exist")));

        Mockito.when(recordingArchiveHelper.getRecordingPath(sourceTarget, recordingName))
                .thenReturn(future2);

        Mockito.when(fs.isReadable(Mockito.any(Path.class))).thenReturn(true);
        Mockito.when(fs.deleteIfExists(Mockito.nullable(Path.class))).thenReturn(false);

        ExecutionException ee =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> cache.get(sourceTarget, "foo", "", true).get());
        MatcherAssert.assertThat(
                ExceptionUtils.getRootCause(ee), Matchers.instanceOf(ArchivePathException.class));

        Mockito.verify(fs, Mockito.atLeastOnce()).deleteIfExists(destinationFile);
    }
}
