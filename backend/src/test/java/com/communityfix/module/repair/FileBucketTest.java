package com.communityfix.module.repair;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileBucketTest {
    private ErrorResponseException failure(String code) {
        var response = mock(ErrorResponse.class);
        when(response.code()).thenReturn(code);
        var failure = mock(ErrorResponseException.class);
        when(failure.errorResponse()).thenReturn(response);
        return failure;
    }
    @Test void anotherInstanceCreatingOurBucketDoesNotFailUpload() throws Exception {
        var client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        doThrow(failure("BucketAlreadyOwnedByYou")).when(client).makeBucket(any(MakeBucketArgs.class));
        assertDoesNotThrow(() -> FileService.ensureBucket(client, "acceptance"));
    }
    @Test void storageAccessDeniedAndForeignBucketAreNotSwallowed() throws Exception {
        for (String code : new String[]{"AccessDenied", "BucketAlreadyExists"}) {
            var client = mock(MinioClient.class);
            var error = failure(code);
            doThrow(error).when(client).makeBucket(any(MakeBucketArgs.class));
            assertSame(error, assertThrows(ErrorResponseException.class,
                    () -> FileService.ensureBucket(client, "acceptance")));
        }
    }
    @Test void existingBucketDoesNotRequireCreatePermission() throws Exception {
        var client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        FileService.ensureBucket(client, "acceptance");
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
    }
}
