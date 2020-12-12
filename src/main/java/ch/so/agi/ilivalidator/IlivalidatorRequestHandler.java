package ch.so.agi.ilivalidator;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.function.aws.MicronautRequestHandler;
import io.micronaut.inject.BeanDefinition;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

import ch.ehi.basics.settings.Settings;
import ch.interlis.iom_j.itf.ItfReader;
import ch.interlis.iom_j.xtf.XtfReader;
import ch.interlis.iox.IoxEvent;
import ch.interlis.iox.IoxException;
import ch.interlis.iox.IoxReader;
import ch.interlis.iox_j.EndTransferEvent;
import ch.interlis.iox_j.StartBasketEvent;
import ch.interlis.iox_j.plugins.IoxPlugin;

import org.interlis2.validator.Validator;
import ch.interlis.iox_j.validator.InterlisFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Introspected
public class IlivalidatorRequestHandler extends MicronautRequestHandler<ValidationSettings, ValidationSettings> { 
    private static final Logger logger = LoggerFactory.getLogger(IlivalidatorRequestHandler.class);

    @Property(name = "app.aws.s3Bucket")
    private String s3Bucket;
    
    @Property(name = "app.aws.prefix")
    private String prefix;
        
    @Property(name = "app.ilivalidator.models")
    private List<String> modelsList;
    
    @Property(name = "app.ilivalidator.config")
    private List<String> configList;
    
    @Property(name = "app.ilivalidator.userFunctions")
    private List<String> userFunctionList;
    
    @Inject
    private ResourceLoader resourceLoader;
    
    private static S3Client s3 = S3Client.builder().build();
    
    @Override
    public ValidationSettings execute(ValidationSettings input) {                       
        // Set connect and read timeout to handle failing interlis repositories.
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "10000");

        // Download data file from S3.
        Path tmpDirectory;
        try {
            tmpDirectory = Files.createTempDirectory("ilivaliator_");
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
                    
        logger.info("input: " + input);
        
        String key = input.getDatafile();
        String subfolder = key.substring(0, key.lastIndexOf("/"));
        String dataFileName = key.substring(key.lastIndexOf("/")+1);
        
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(key)
                .build();

        ResponseInputStream ris = s3.getObject(getObjectRequest);
        File dataFile = Paths.get(tmpDirectory.toFile().getAbsolutePath(), dataFileName).toFile();
        try {
            Files.copy(ris, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
                
        // Settings
        Settings settings = new Settings();
        settings.setValue(Validator.SETTING_ILIDIRS, Validator.SETTING_DEFAULT_ILIDIRS);
        String logFileName = dataFile.getAbsolutePath() + ".log"; 
        settings.setValue(Validator.SETTING_LOGFILE, logFileName);
        settings.setValue(Validator.SETTING_ALL_OBJECTS_ACCESSIBLE, Validator.TRUE);
        
        // Custom functions
        Map<String,Class> userFunctions = new HashMap<String,Class>();
        try {
            for (String userFunction : userFunctionList) {
                Class clazz = Class.forName(userFunction);
                IoxPlugin plugin=(IoxPlugin)clazz.newInstance();
                userFunctions.put(((InterlisFunction) plugin).getQualifiedIliName(), clazz); 
            }
            settings.setTransientObject(ch.interlis.iox_j.validator.Validator.CONFIG_CUSTOM_FUNCTIONS, userFunctions);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
        
        // Zusätzliche Modelle (z.B. Validierungsmodelle)
        // Modelle sollten in einer Modellablage sein. Eventuell gibt es
        // exotische Prüfungen, die noch nicht via View funktionieren.
        for (String modelFileName : modelsList) {
            InputStream is = resourceLoader.getResourceAsStream("classpath:"+modelFileName).get();
            File modelFile = Paths.get(tmpDirectory.toFile().getAbsolutePath(), modelFileName).toFile();
            try {
                Files.copy(is, modelFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        
        // Config-Files sollten mit einem Datarepo funktionieren (Issue
        // auf github gemacht).
        for (String configFileName : configList) {
            InputStream is = resourceLoader.getResourceAsStream("classpath:"+configFileName).get();
            File configFile = Paths.get(tmpDirectory.toFile().getAbsolutePath(), configFileName).toFile();
            try {
                Files.copy(is, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        
        // TODO: expose "use config file" to user / gui
        
        // Config-Datei anwenden, falls sie vorhanden ist. Es gilt
        // die Konvention: Kleingeschriebener Modellname == Config-Datei ohne Dateiextension. 
        String doConfigFile = "true";
        if (doConfigFile != null) {
            String modelName;
            try {
                modelName = getModelNameFromTransferFile(dataFile.getAbsolutePath());
            } catch (IoxException e) {
                throw new RuntimeException(e.getMessage());
            }
            String[] fileNames = tmpDirectory.toFile().list();
           
            for (String fileName : fileNames) {
                if (fileName.startsWith(modelName.toLowerCase())) {
                    settings.setValue(Validator.SETTING_CONFIGFILE, Paths.get(tmpDirectory.toFile().getAbsolutePath(), fileName).toFile().getAbsolutePath());
                }
            }
        }

        // Run validation
        boolean valid = Validator.runValidation(dataFile.getAbsolutePath(), settings);
        
        key = subfolder + "/" +  logFileName.substring(logFileName.lastIndexOf("/")+1);
        input.setLogfile(key);
        input.setValid(valid);
        
        // Upload logfile.
        s3.putObject(PutObjectRequest.builder().bucket(s3Bucket).key(key).build(), Paths.get(logFileName));
        s3.putObjectAcl(PutObjectAclRequest.builder().bucket(s3Bucket).key(key).acl(ObjectCannedACL.PUBLIC_READ).build());

        return input;
    }
    
    /*
     * Figure out INTERLIS model name from INTERLIS transfer file. Works with ili1
     * and ili2.
     */
    private String getModelNameFromTransferFile(String transferFileName) throws IoxException {
        String model = null;
        String ext = getFileExtension(transferFileName);
        IoxReader ioxReader = null;

        try {
            File transferFile = new File(transferFileName);

            if (ext.equalsIgnoreCase("itf")) {
                ioxReader = new ItfReader(transferFile);
            } else {
                ioxReader = new XtfReader(transferFile);
            }

            IoxEvent event;
            StartBasketEvent be = null;
            do {
                event = ioxReader.read();
                if (event instanceof StartBasketEvent) {
                    be = (StartBasketEvent) event;
                    break;
                }
            } while (!(event instanceof EndTransferEvent));

            ioxReader.close();
            ioxReader = null;

            if (be == null) {
                throw new IllegalArgumentException("no baskets in transfer-file");
            }

            String namev[] = be.getType().split("\\.");
            model = namev[0];

        } catch (IoxException e) {
            e.printStackTrace();
            throw new IoxException("could not parse file: " + new File(transferFileName).getName());
        } finally {
            if (ioxReader != null) {
                try {
                    ioxReader.close();
                } catch (IoxException e) {
                    e.printStackTrace();
                    throw new IoxException(
                            "could not close interlise transfer file: " + new File(transferFileName).getName());
                }
                ioxReader = null;
            }
        }
        return model;
    } 

    /*
     * Get the extension of a file.
     */
    private static String getFileExtension(String fileName) {
        if(fileName.lastIndexOf(".") != -1 && fileName.lastIndexOf(".") != 0)
        return fileName.substring(fileName.lastIndexOf(".")+1);
        else return "";
    }

}
