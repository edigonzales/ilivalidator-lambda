package ch.so.agi.ilivalidator;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.function.aws.MicronautRequestHandler;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;

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

@Introspected
public class IlivalidatorRequestHandler extends MicronautRequestHandler<ValidationSettings, ValidationSettings> {        
    private static S3Client s3 = S3Client.builder().build();
    
    @Property(name = "app.aws.s3Bucket")
    private String s3Bucket;
    
    @Property(name = "app.aws.prefix")
    private String prefix;
        
    @Property(name = "app.ilivalidator.userFunctions")
    List<String> userFunctionList;
    
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
            
        String key = input.getDatafile();
        
        String[] keys = key.split("/");
        String subfolder = keys[0];
        String dataFileName = keys[1];
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(key)
                .build();

        ResponseInputStream is = s3.getObject(getObjectRequest);
        File dataFile = Paths.get(tmpDirectory.toFile().getAbsolutePath(), dataFileName).toFile();
        try {
            Files.copy(is, dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
                
        // Settings
        Settings settings = new Settings();
        settings.setValue(Validator.SETTING_ILIDIRS, Validator.SETTING_DEFAULT_ILIDIRS);
        String logFileName = dataFile.getAbsolutePath() + ".log"; 
        //System.out.println("logFileName: " + logFileName);
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
        
        // Zusätzliche Modelle (z.B. Validierungsmodelle) und Config-Files
        // herunterladen.
        // Modelle sollten in einer Modellablage sein. Eventuell gibt es
        // exotische Prüfungen, die noch nicht via View funktionieren.
        // Config-Files sollten mit einem Datarepo funktionieren (Issue
        // auf github gemacht).
        ListObjectsRequest listObjects = ListObjectsRequest
                .builder()
                .bucket(s3Bucket)
                .prefix(prefix)
                .build();
        ListObjectsResponse res = s3.listObjects(listObjects);
        List<S3Object> objects = res.contents();
        GetObjectRequest gor;
        ResponseInputStream ris;
        for (ListIterator iterVals = objects.listIterator(); iterVals.hasNext(); ) {
            S3Object s3object = (S3Object) iterVals.next();
            String additionalFileKey = s3object.key();
            if (s3object.key().matches("(?i).*\\.(ili|toml)$")) {
                gor = GetObjectRequest.builder()
                        .bucket(s3Bucket)
                        .key(additionalFileKey)
                        .build();
                ris = s3.getObject(gor);
                String additionalFileName = additionalFileKey.substring(additionalFileKey.lastIndexOf("/")+1);
                File additionalFile = Paths.get(tmpDirectory.toFile().getAbsolutePath(), additionalFileName).toFile();
                try {
                    Files.copy(ris, additionalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage());
                }
            }
         }
        
        // TODO: expose "use config file" to user / gui
        
        // Config-Datei anwenden, falls eine vorhanden ist. Es gilt
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
