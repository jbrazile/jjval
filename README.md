# jjval
Java JSON schema-based validator
##
This is a simple wrapper around the [justify](https://github.com/leadpony/justify) and [everit](https://github.com/everit-org/json-schema) JSON schema validators, packaged as a standalone jar.
# usage
The following usage message is when run with no arguments:

```
At least one of -vj or -ve needs to be specified
usage: jjval [-vj][-ve] -s [schema] file...
    -vj		validate with justify
    -ve		validate with everit
    -s [schema]	JSON schema for validation purposes
```
##
An example command line:

```
java -jar jjval.jar -vj -ve -s schema.json file1.json file2.json file3.json
```
