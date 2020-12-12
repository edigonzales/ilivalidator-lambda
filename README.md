# ilivalidator-lambda

## Todo
Siehe https://github.com/edigonzales/ilivalidator-web-service-client

## Build
```
./gradlew clean shadowJar
```

## Testing

### Lokal
Während der Tests werden Daten im Verzeichnis `test/local` im Bucket `ch.so.agi.ilivalidator` ausgetauscht.

### AWS
```
{
  "datafile": "test/remote/254900.itf"
}
```
Das kann anscheinend nicht via Cloudformation gesetzt werden, da es als Cookie gespeichert wird.

## Lambda
### Erstellen der Funktion
```
aws cloudformation create-stack --stack-name ilivalidator-lambda-stack \
  --template-body file://ilivalidator_stack.yml \
  --capabilities CAPABILITY_NAMED_IAM
```

```
aws cloudformation delete-stack --stack-name ilivalidator-lambda-stack
```

`Code` darf nicht NULL sein. Im Bucket `ch.so.agi.ilivalidator` liegt die Jar-Datei `build/libs/ilivalidator-lambda-1.0.9999.jar`. Diese dient nur dem erstmaligen Anlegen des Stacks. Anschliessend wird via CI/CD deployed.

Version/Alias wird (noch) nicht im Cloudformation Stack behandelt, sondern manuell (siehe nächstes Kapitel).

### Version / Alias (Staging-Umgebungen)
Zuerst muss im `unqualified` Qualifier eine Version der Funktion publiziert werden: `Actions - Publish new Version`. Mit `Actions - Create alias` können Aliasnamen erstellt werden, z.B. `PROD`, `INT` und `TEST`. Jedem Alias muss mit einer Version verknüpft werden. Dem Test-Alias wird `$LATEST` zugewiesen. 

Will man in dem Prod-Alias eine neue Version zuweisen, muss man im Unqualified-Qualifier zuerst eine neue Version deployen, in den `Qualifiers - PROD` wechseln und unter `Alias configuration` dem Alias die neue Version zuweisen.
