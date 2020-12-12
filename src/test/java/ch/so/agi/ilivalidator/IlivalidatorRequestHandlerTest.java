package ch.so.agi.ilivalidator;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@MicronautTest
public class IlivalidatorRequestHandlerTest {
    private static final Logger log = LoggerFactory.getLogger(IlivalidatorRequestHandlerTest.class);

    private static IlivalidatorRequestHandler ilivalidatorRequestHandler;
    private static S3Client s3;
    private static String s3Bucket = "ch.so.agi.ilivalidator";
    private static String subFolder = "test/local";
    
    @BeforeAll
    public static void setupServer() {
        ilivalidatorRequestHandler = new IlivalidatorRequestHandler();

        s3 = S3Client.builder().build();
    }

    @AfterAll
    public static void stopServer() {
        if (ilivalidatorRequestHandler != null) {
            ilivalidatorRequestHandler.getApplicationContext().close();
        }

        if (s3 != null) {
            s3.close();  
        }
    }

    @Test
    public void testHandler_Ok() throws Exception {
        // Upload data file to S3
        File datafile = new File("src/test/data/254900.itf");
        String key = subFolder + "/" + datafile.getName();
        s3.putObject(PutObjectRequest.builder().bucket(s3Bucket).key(key).build(), datafile.toPath());
        
        // Run validation
        ValidationSettings validationSettings = new ValidationSettings(); 
        validationSettings.setDatafile(key);        
        validationSettings = ilivalidatorRequestHandler.execute(validationSettings);

        // Check results
        assertTrue(validationSettings.isValid());
        assertNotNull(validationSettings.getLogfile());
        
        // Clean up
        ArrayList<ObjectIdentifier> toDelete = new ArrayList<ObjectIdentifier>();
        toDelete.add(ObjectIdentifier.builder().key(key).build());
        toDelete.add(ObjectIdentifier.builder().key(key + ".log").build());
        
        DeleteObjectsRequest dor = DeleteObjectsRequest.builder()
                .bucket(s3Bucket)
                .delete(Delete.builder().objects(toDelete).build())
                .build();
        s3.deleteObjects(dor);
    }
}
