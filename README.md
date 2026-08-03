# jjval
Java JSON/YAML/XML validator

A standalone jar that drives several JSON Schema validators -
[justify](https://github.com/leadpony/justify),
[everit](https://github.com/everit-org/json-schema),
[networknt](https://github.com/networknt/json-schema-validator),
[json-sKema](https://github.com/erosb/json-sKema), and
[jsonschemafriend](https://github.com/jimblackler/jsonschemafriend) -
plus W3C XSD and DTD-based XML validation.  Having multiple engines
in one command makes it easy to cross-check how different implementations
judge the same document and schema.

# usage

```
usage: jjval <mode> [-s schema] [-d dtd] [--draft 04|06|07|2019|2020] [-nv] [-q] file...
  exactly one mode must be given:
    -vj         validate json with justify (jakarta.json)
    -ve         validate json with everit (org.json)
    -vn         validate json with networknt (jackson)
    -vk         validate json with json-sKema (draft 2020-12 only)
    -vf         validate json with jsonschemafriend
    -vy         validate yaml with networknt (jackson-dataformat-yaml)
    -vx         validate xml against a dtd given with -d
    -vs         validate xml against a w3c xsd given with -s
    -pj         parse only (passthrough) with justify (jakarta.json)
    -pe         parse only (passthrough) with everit (org.json)
    -pn         parse only (passthrough) with networknt (jackson)
  options:
    -s (schema) JSON schema (or .xsd for -vs) to validate against
    -d (dtd)    DTD file to validate against (used by -vx)
    --draft (04|06|07|2019|2020)
                dialect to assume when the schema has no $schema keyword
    -nv         don't show version
    -q          quiet mode - no output, run only for the exit code
    -h          show this help
  exit: 0=ok 1=syntax error 2=validation error 3=bad schema 4=file i/o 5=usage
```

Findings (syntax errors, schema violations) go to **stdout**; progress and
summary messages go to **stderr**, so the two streams can be redirected
independently.

## exit codes

| code | meaning |
|-----:|---------|
| 0 | success ? no syntax and no validation problems |
| 1 | syntax error in an input document |
| 2 | at least one schema/DTD/XSD validation problem |
| 3 | the supplied schema could not be parsed or understood |
| 4 | an input file could not be read |
| 5 | command line usage error |

The exit code reflects the **first** problem category found; all supplied
files are always processed.

## engine comparison

| flag | library | draft support | default dialect |
|------|---------|--------------|-----------------|
| `-vj` | [justify](https://github.com/leadpony/justify) | 04 / 06 / 07 | from `$schema`, else 07 |
| `-ve` | [everit](https://github.com/everit-org/json-schema) | 04 / 06 / 07 | **04** (use `--draft 07`) |
| `-vn` | [networknt](https://github.com/networknt/json-schema-validator) | 04 / 06 / 07 / 2019 / **2020** | 2020-12 |
| `-vk` | [json-sKema](https://github.com/erosb/json-sKema) | **2020-12 only** | 2020-12 |
| `-vf` | [jsonschemafriend](https://github.com/jimblackler/jsonschemafriend) | 03 / 04 / 06 / 07 / 2019 / **2020** | from `$schema`, else 2020 |
| `-vy` | networknt + YAML | same as `-vn` | 2020-12 |
| `-vx` | JDK SAX parser | DTD | n/a |
| `-vs` | JDK XML schema | W3C XSD | n/a |

Use `--draft` to pin the dialect when comparing engines against the same
schema that has no `$schema` keyword (especially `-ve` which defaults to
draft-04 and ignores modern keywords like `const` without it).

## examples

Validate JSON documents against a JSON schema:
```
java -jar jjval.jar -vj -s schema.json file1.json file2.json
```

Cross-check two engines on the same file:
```
java -jar jjval.jar -vn --draft 07 -s schema.json data.json
java -jar jjval.jar -vj --draft 07 -s schema.json data.json
```

Validate YAML against a JSON schema:
```
java -jar jjval.jar -vy -s schema.json data.yaml
```

Validate XML against a DTD or XSD:
```
java -jar jjval.jar -vx -d foo.dtd   file.xml
java -jar jjval.jar -vs -s schema.xsd file.xml
```

Parse only (check well-formedness without a schema):
```
java -jar jjval.jar -pj file.json
```

Use in a shell script silently:
```
java -jar jjval.jar -q -vn -s schema.json data.json || echo "invalid"
```

# building

```
mvn verify
```

`mvn verify` builds the shaded jar and runs the CLI integration tests defined
in `pom.xml` (`maven-antrun-plugin`, execution `cli-test`).  Every case runs
the jar as a subprocess and asserts its exit code, `stdout` and `stderr`
against the fixtures in `src/test/resources/`
(`<mode>.expected.<case>.<stream>.txt`).

After an intentional output change, regenerate all fixtures with:
```
mvn package && ./src/test/regenerate-expected.sh
```
The script prints the observed exit code of every case so the `expected=`
attributes in `pom.xml` can be kept in sync.
