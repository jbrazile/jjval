# jjval
Java JSON schema-based validator
##
This is a simple wrapper around the [justify](https://github.com/leadpony/justify) and [everit](https://github.com/everit-org/json-schema) JSON schema validators, packaged as a standalone jar.
# usage
The following usage message is when run with no arguments:

```
At least one of -vj, -ve, -vs, -pj, -pe must be specified
usage: jjval [-vj][-ve] -s [schema] file...
    -vj		validate json with justify
    -ve		validate json with everit
    -vx		validate xml with standard jdk
    -pj		passthrough with justify (jakarta.json)
    -pe		passthrough with everit (org.json)
    -nv		don't show version
    -s (schema)	JSON schema for validation purposes
    -d (dtd)	DTD document for xml validation purposes
    -q		quiet mode - no validation output, run only for exit code
```
##
An example command line:

```
java -jar jjval.jar -vj -s schema.json file1.json file2.json file3.json
```

##
It can also validate xml files against a provided DTD.
```
java -jar jjval.jar -vx -d foo.dtd file1.xml file2.xml file3.xml
```
