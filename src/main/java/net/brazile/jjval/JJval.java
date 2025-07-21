/**
 * Java JSON schema based validator. This is a simple driver for the justify
 * everit and networknt JSON schema-based validators, packaged as a standalone jar.
 * <p>
 * MIT License
 * <p>
 * Copyright (c) Jason Brazile
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.brazile.jjval;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import jakarta.json.Json;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Java-based JSON validator optionally using JSON-Schema.
 */
public class JJval {
  private static final String VERSION                 = "v1.0.7";
  private static final int SUCCESS                    = 0;
  private static final int ERROR_SYNTAX               = 1;
  private static final int ERROR_VALIDATION           = 2;
  private static final int ERROR_NULL                 = 3;
  private static final int ERROR_FILEIO               = 4;
  private static final int ERROR_USAGE                = 5;
  private static final String BUILD_TIME              = "Build-Time";

  private boolean allCorrect                          = true;
  private boolean quietMode                           = false;
  private boolean showVersion                         = true;
  private String jsonSchema                           = null;
  private String xmlDtd                               = null;
  private boolean validateJustify                     = false;
  private boolean validateEverit                      = false;
  private boolean validateNetworknt                   = false;
  private boolean validateXml                         = false;
  private boolean passthroughJustify                  = false;
  private boolean passthroughEverit                   = false;
  private boolean passthroughNetworknt                = false;
  private boolean matchingDtdProvided                 = false;
  private List<String> files                          = new ArrayList<>();
  private JsonValidationService jService              = null;
  private org.leadpony.justify.api.JsonSchema jSchema = null;
  private com.networknt.schema.JsonSchema nSchema     = null;
  private Schema eSchema                              = null;

  public void setValidateNetworknt(boolean flag) {
    this.validateNetworknt = flag;
  }
  public void setValidateJustify(boolean flag) {
    this.validateJustify = flag;
  }
  public void setValidateEverit(boolean flag) {
    this.validateEverit = flag;
  }
  public void setValidateXml(boolean flag) {
    this.validateXml = flag;
  }
  public void setPassthroughJustify(boolean flag) {
    this.passthroughJustify = flag;
  }
  public void setPassthroughEverit(boolean flag) {
    this.passthroughEverit = flag;
  }
  public void setPassthroughNetworknt(boolean flag) {
    this.passthroughNetworknt = flag;
  }
  public void setQuietMode(boolean flag) {
    this.quietMode = flag;
  }
  public void setShowVersion(boolean flag) {
    this.showVersion = flag;
  }
  public void setJsonSchemaFile(String jsonSchemaFile) {
    this.jsonSchema = jsonSchemaFile;
  }
  public void setXmlDtdFile(String xmlDtdFile) {
    this.xmlDtd = xmlDtdFile;
  }
  public void setFiles(List<String> files) {
    this.files = files;
  }

  /**
   * Utility class used to print validation errors when using the justify engine.
   */
  class PrintingProblemHandler implements ProblemHandler {
    public void handleProblems(List<org.leadpony.justify.api.Problem> problems) {
      for(org.leadpony.justify.api.Problem problem : problems) {
        allCorrect = false;
        if (!quietMode) { System.out.println(problem.toString()); }
      }
    }
  }

  /**
   * Print usage and exit with failure.
   * @param msg error message to print with usage information.
   */
  private static void usage(String msg) {
    System.err.println(String.format("%s%nusage: %s [-vj][-ve][-vn] -s [schema] file...", msg, "jjval"));
    System.err.println("    -vj\t\tvalidate json with justify");
    System.err.println("    -ve\t\tvalidate json with everit");
    System.err.println("    -vn\t\tvalidate json with networknt");
    System.err.println("    -vx\t\tvalidate xml with standard jdk");
    System.err.println("    -pj\t\tpassthrough with justify (jakarta.json)");
    System.err.println("    -pe\t\tpassthrough with everit (org.json)");
    System.err.println("    -nv\t\tdon't show version");
    System.err.println("    -s (schema)\tJSON schema for validation purposes");
    System.err.println("    -d (dtd)\tDTD document for xml validation purposes");
    System.err.println("    -q\t\tquiet mode - no validation output, run only for exit code");
    System.exit(ERROR_USAGE);
  }

  /**
   * Validate a JSON file optionally against a JSON schema with either the everit (org.json) or justify (jakarta.json) validation engines.
   * @param args command line arguments passed through.
   * @return integer result to use as program return value.
   */
  private int validate(String[] args) {
    if (showVersion && !quietMode) {
      System.err.println(String.format("jjval (version: %s  build: %s)", VERSION, getJarAttr(BUILD_TIME)));
    }

    // Validate arguments
    validateArguments();

    // Setup validators
    int retval = setupValidators();
    if (retval != SUCCESS) {
      return retval;
    }

    // Process all given files
    PrintingProblemHandler handler = new PrintingProblemHandler();
    for (String file : files) {
      Path path = Paths.get(file);
      if (validateJustify) {
        retval = processJustifyValidation(file, path, handler);
      } else if (validateEverit) {
        retval = processEveritValidation(file, path);
      } else if (validateNetworknt) {
        retval = processNetworkntValidation(file, path);
      } else if (validateXml) {
        retval = processXmlValidation(file);
      } else if (passthroughJustify) {
        retval = processJustifyPassthrough(file);
      } else if (passthroughEverit) {
        retval = processEveritPassthrough(file);
      } else if (passthroughNetworknt) {
        retval = processNetworkntPassthrough(file, path);
      }
    }

    // Final validation result
    if (validateJustify || validateEverit || validateNetworknt || (validateXml && matchingDtdProvided)) {
      System.err.println(allCorrect ? "No validation issues encountered." : "At least one validation issue encountered.");
    }
    if ((retval == SUCCESS) && !allCorrect) {
      retval = ERROR_VALIDATION;
    }
    return retval;
  }

  /**
   * Validate the command line arguments.
   * If any validation fails, print usage and exit with an error code.
   */
  private void validateArguments() {
    if (!validateJustify && !validateEverit && !validateNetworknt && !passthroughJustify && !passthroughEverit && !validateXml) {
      usage("At least one of -vj, -ve, -vs, -pj, -pe must be specified");
    }
    if ((validateJustify || validateEverit || validateNetworknt) && ((jsonSchema == null) || !(new File(jsonSchema)).canRead())) {
      usage("with -vj, -ve, -vn, a readable schema file must be specified with -s");
    }
    if (files.isEmpty()) {
      usage("At least one file to validate must be specified");
    }
  }

  /**
   * Setup the JSON schema validators.
   * @return SUCCESS if setup was successful, otherwise an error code.
   */
  private int setupValidators() {
    try {
      if (validateJustify) {
        jService = JsonValidationService.newInstance();
        try (InputStream schemaStream = Files.newInputStream(Paths.get(jsonSchema))) {
          jSchema = jService.readSchema(schemaStream);
        }
      }
      if (validateEverit) {
        eSchema = SchemaLoader.load(new JSONObject(new String(Files.readAllBytes(Paths.get(jsonSchema)), StandardCharsets.UTF_8)));
      }
      if (validateNetworknt) {
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        nSchema = schemaFactory.getSchema(Files.newInputStream(Paths.get(jsonSchema)));
      }
    } catch (IOException e) {
      System.out.println("Error reading Schema: " + e.getMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  /**
   * Process the JSON file with the justify validation engine.
   * @param file the file to validate
   * @param path the path to the file
   * @param handler the problem handler for justify
   * @return SUCCESS if validation was successful, otherwise an error code.
   */
  private int processJustifyValidation(String file, Path path, PrintingProblemHandler handler) {
    System.err.println(String.format("Validating '%s' with justify...", file));
    try (InputStream jsonStream = Files.newInputStream(path);
         jakarta.json.stream.JsonParser jParser = jService.createParser(jsonStream, jSchema, handler)) {
      while (jParser.hasNext()) {
        jParser.next();
      }
    } catch (IOException e) {
      System.out.println("Error reading JSON file: " + e.getMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  /**
   * Process the JSON file with the everit validation engine.
   * @param file the file to validate
   * @param path the path to the file
   * @return SUCCESS if validation was successful, otherwise an error code.
   */
  private int processEveritValidation(String file, Path path) {
    System.err.println(String.format("Validating '%s' with everit...", file));
    try {
      String inputTxt = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      int i = 0;
      while (i < inputTxt.length() && Character.isWhitespace(inputTxt.charAt(i))) {
        i++;
      }
      if (inputTxt.charAt(i) == '[') {
        eSchema.validate(new org.json.JSONArray(inputTxt));
      } else {
        eSchema.validate(new org.json.JSONObject(inputTxt));
      }
    } catch (IOException e) {
      System.out.println(e.getLocalizedMessage());
      return ERROR_FILEIO;
    } catch (ValidationException e) {
      allCorrect = false;
      if (!quietMode) {
        System.out.println(e.toJSON().toString(2));
      }
    }
    return SUCCESS;
  }

  /**
   * Process the JSON file with the networknt validation engine.
   * @param file the file to validate
   * @param path the path to the file
   * @return SUCCESS if validation was successful, otherwise an error code.
   */
  private int processNetworkntValidation(String file, Path path) {
    System.err.println(String.format("Validating '%s' with networknt...", file));
    try (InputStream jsonStream = Files.newInputStream(path)) {
      com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(jsonStream);
      Set<ValidationMessage> validationMessages = nSchema.validate(jsonNode);
      if (!validationMessages.isEmpty()) {
        allCorrect = false;
        if (!quietMode) {
          for (ValidationMessage msg : validationMessages) {
            System.out.println(msg.getMessage());
          }
        }
      }
    } catch (IOException e) {
      System.out.println(e.getLocalizedMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  /**
   * Process the XML file with the standard JDK validation.
   * @param file the file to validate
   * @return SUCCESS if validation was successful, otherwise an error code.
   */
  private int processXmlValidation(String file) {
    int retval = SUCCESS;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setValidating(true);
      factory.setNamespaceAware(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setEntityResolver((publicId, systemId) -> {
        matchingDtdProvided = false;
        if (xmlDtd != null && !xmlDtd.isEmpty()) {
          String fName = new File(xmlDtd).getName();
          if (systemId != null) {
            if (systemId.endsWith(fName)) {
              System.err.println(String.format("Validating '%s' with dtd '%s'...", file, xmlDtd));
              matchingDtdProvided = true;
              return new InputSource(new File(xmlDtd).toURI().toString());
            } else {
              System.err.println(String.format("NOT Validating (passthrough) '%s' (expected dtd='%s' but provided dtd='%s')...",
                  file, new File(systemId).getName(), fName));
              return null;
            }
          }
        }
        System.err.println(String.format("NOT Validating (passthrough) '%s' with jdk..", file));
        return null;
      });

      builder.setErrorHandler(new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
          allCorrect = false;
          if (!quietMode) {
            System.out.println("Warning: " + exception.toString());
          }
        }

        @Override
        public void error(SAXParseException exception) {
          allCorrect = false;
          if (!quietMode) {
            System.out.println("Error: " + exception.toString());
          }
        }

        @Override
        public void fatalError(SAXParseException exception) {
          allCorrect = false;
          if (!quietMode) {
            System.out.println("Fatal error: " + exception.toString());
          }
        }
      });

      File xmlFile = new File(file);
      builder.parse(xmlFile);
      retval = SUCCESS;

    } catch (ParserConfigurationException | SAXException | IOException e) {
      allCorrect = false;
      if (!quietMode) {
        System.out.println("Validation error: " + e.toString());
      }
      retval = ERROR_VALIDATION;
    }
    return retval;
  }

  /**
   * Process the JSON file with the justify validation engine in passthrough mode.
   * This means it will not validate against a schema but will parse the JSON.
   * @param file the file to process
   * @return SUCCESS if processing was successful, otherwise an error code.
   */
  private int processJustifyPassthrough(String file) {
    int retval = SUCCESS;
    System.err.println(String.format("NOT validating (passthrough) '%s' with justify (jakarta.json)...", file));

    try (FileInputStream fileInputStream = new FileInputStream(file);
         jakarta.json.stream.JsonParser parser = Json.createParser(fileInputStream)) {
      while (parser.hasNext()) {
        parser.next();
      }
    } catch (FileNotFoundException e) {
      retval = ERROR_FILEIO;
      System.out.println(e.getLocalizedMessage());
    } catch (Exception e) {
      retval = ERROR_SYNTAX;
      System.out.println(e.getLocalizedMessage());
    }

    return retval;
  }

  /**
   * Process the JSON file with the everit validation engine in passthrough mode.
   * This means it will not validate against a schema but will parse the JSON.
   * @param file the file to process
   * @return SUCCESS if processing was successful, otherwise an error code.
   */
  private int processEveritPassthrough(String file) {
    int retval = SUCCESS;
    System.err.println(String.format("NOT validating (passthrough) '%s' with everit (org.json)...", file));
    JSONTokener tokener = null;
    try {
      tokener = new JSONTokener(new FileInputStream(file));
    } catch (FileNotFoundException e) {
      retval = ERROR_FILEIO;
      System.out.println(e.getLocalizedMessage());
    }
    if (tokener == null) {
      retval = ERROR_NULL;
    } else {
      try {
        while (tokener.more()) {
          tokener.next();
        }
      } catch (org.json.JSONException e) {
        retval = ERROR_SYNTAX;
        System.out.println(e.getLocalizedMessage());
      }
    }
    return retval;
  }

  /**
   * Process the JSON file with the networknt validation engine in passthrough mode.
   * @param file the file to process
   * @param path the path to the file
   * @return SUCCESS if processing was successful, otherwise an error code.
   */
  private int processNetworkntPassthrough(String file, Path path) {
    int retval = SUCCESS;
    System.err.println(String.format("NOT validating (passthrough) '%s' with networknt (jackson)...", file));
    try (InputStream jsonStream = Files.newInputStream(path);
         com.fasterxml.jackson.core.JsonParser parser = new com.fasterxml.jackson.core.JsonFactory().createParser(jsonStream)) {
      while (parser.nextToken() != null) {
        // Quietly parse the JSON file without validation
      }
    } catch (IOException e) {
      retval = ERROR_FILEIO;
      System.out.println(e.getLocalizedMessage());
    }
    return retval;
  }

  /**
   * Get a string attribute from the containing jar.
   * @param key the attribute to obtain
   * @return the string value of the attribute or '(unknown)'
   */
  private static String getJarAttr(String key) {
    String attr = "(unknown)";
    try (InputStream manifestStream = JJval.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF")) {
      if (manifestStream != null) {
        Manifest manifest = new Manifest(manifestStream);
        Attributes attributes = manifest.getMainAttributes();
        String value = attributes.getValue(key);
        if (value != null) {
          attr = value;
        }
      }
    } catch (IOException e) {
      System.err.println("Error reading manifest attribute: " + e.getMessage());
    }
    return attr;
  }

  /**
   * Main driver.
   * @param args arguments specifiying schema-based validation or not and which engine to use.
   */
  public static void main(String[] args) {
    JJval jjval = new JJval();
    List<String> filesToValidate = new ArrayList<>();

    // parse command line
    int state = 0;
    for (String arg: args) {
      switch(arg) {
        case "-vj": jjval.setValidateJustify(true); break;
        case "-ve": jjval.setValidateEverit(true); break;
        case "-vn": jjval.setValidateNetworknt(true); break;
        case "-vx": jjval.setValidateXml(true); break;
        case "-pj": jjval.setPassthroughJustify(true); break;
        case "-pe": jjval.setPassthroughEverit(true); break;
        case "-pn": jjval.setPassthroughNetworknt(true); break;
        case "-nv": jjval.setShowVersion(false); break;
        case "-q":  jjval.setQuietMode(true); break;
        case "-s":  state = 1; break;
        case "-d":  state = 2; break;
        default:
          switch (state) {
            case 1:
              jjval.setJsonSchemaFile(arg);
              state = 0;
              break;
            case 2:
              jjval.setXmlDtdFile(arg);
              state = 0;
              break;
            default:
              filesToValidate.add(arg);
              break;
          }
          break;
      }
    }
    jjval.setFiles(filesToValidate);
    System.exit(jjval.validate(args));
  }
}

