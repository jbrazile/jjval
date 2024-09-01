/**
 * Java JSON schema based validator. This is a simple driver for the justify
 * and everit JSON schema-based validators, packaged as a standalone jar.
 *
 * MIT License
 *
 * Copyright (c) 2020 Jason Brazile
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.json.Json;
import javax.json.stream.JsonParser.Event;
import javax.json.stream.JsonParser;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.leadpony.justify.api.JsonSchema;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;


/**
 * Java-based JSON validator optionally using JSON-Schema.
 */
public class JJval {
  private static final String VERSION       = "v1.0.5";
  private static final int SUCCESS          = 0;
  private static final int ERROR_SYNTAX     = 1;
  private static final int ERROR_VALIDATION = 2;
  private static final int ERROR_NULL       = 3;
  private static final int ERROR_FILEIO     = 4;
  private static final int ERROR_USAGE      = 5;
  private static final String BUILD_TIME    = "Build-Time";

  private boolean allCorrect                = true;
  private boolean quietMode                 = false;
  private boolean showVersion               = true;
  private String jsonSchema                 = null;;
  private String xmlDtd                     = null;;
  private boolean validateJustify           = false;
  private boolean validateEverit            = false;
  private boolean validateXml               = false;
  private boolean passthroughJustify        = false;
  private boolean passthroughEverit         = false;
  private boolean matchingDtdProvided       = false;
  private List<String> files                = new ArrayList<>();
  private JsonValidationService jService    = null;
  private JsonSchema jSchema                = null;
  private Schema eSchema                    = null;

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
    System.err.println(String.format("%s\nusage: %s [-vj][-ve] -s [schema] file...", msg, "jjval"));
    System.err.println("    -vj\t\tvalidate json with justify");
    System.err.println("    -ve\t\tvalidate json with everit");
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
  public int validate(String[] args) {
    if (showVersion && !quietMode) {
      System.err.println(String.format("jjval (version: %s  build: %s)", VERSION, getJarAttr(BUILD_TIME)));
    }
    int retval = SUCCESS;

    // validate arguments
    if (!validateJustify && !validateEverit && !passthroughJustify && !passthroughEverit && !validateXml) { usage("At least one of -vj, -ve, -vs, -pj, -pe must be specified");}
    if ((validateJustify || validateEverit) && ((jsonSchema == null) || !(new File(jsonSchema)).canRead())) {usage("with -vj, -ve, a readable schema file must be specified with -s");}
    if (files.size() < 1) {usage("At least one file to validate must be specified");}

    // setup validator(s)
    if (validateJustify) {
      jService = JsonValidationService.newInstance();
      jSchema = jService.readSchema(Paths.get(jsonSchema));
    }
    if (validateEverit) {
      try {
        eSchema = SchemaLoader.load(new JSONObject(new String(Files.readAllBytes(Paths.get(jsonSchema)), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        retval = ERROR_FILEIO;
        System.out.println(e.getLocalizedMessage());
      }
    }

    // process all given files
    PrintingProblemHandler handler = new PrintingProblemHandler();
    for (String file: files) {
      if (validateJustify) {
        System.err.println(String.format("Validating '%s' with justify...", file));
        JsonParser jParser = jService.createParser(Paths.get(file), jSchema, handler);
        while(jParser.hasNext()) { Event jevent = jParser.next(); }
      }
      if (validateEverit) {
        System.err.println(String.format("Validating '%s' with everit...", file));
        if (retval == SUCCESS) {
          try {
            String inputTxt = null;
            try {
              inputTxt = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
              retval = ERROR_FILEIO;
              System.out.println(e.getLocalizedMessage());
            }
            int i = 0;
            while (i < inputTxt.length() && Character.isWhitespace(inputTxt.charAt(i))) {
              i++;
            }
            if (inputTxt.charAt(i) == '[') {
              eSchema.validate(new org.json.JSONArray(inputTxt));
            } else {
              eSchema.validate(new org.json.JSONObject(inputTxt));
            }
          } catch (ValidationException e) {
            allCorrect = false;
            if (!quietMode) {
              System.out.println(e.toJSON().toString(2));
            }
          }
        }
      }
      if (validateXml) {
        try {
          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          factory.setValidating(true);
          factory.setNamespaceAware(true);
          DocumentBuilder builder = factory.newDocumentBuilder();
          builder.setEntityResolver(new EntityResolver() {

            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
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
            }
          });

          builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
              allCorrect = false;
              if (!quietMode) {
                System.out.println("Warning: " + exception.toString());
              }
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
              allCorrect = false;
              if (!quietMode) {
                System.out.println("Error: " + exception.toString());
              }
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
              allCorrect = false;
              if (!quietMode) {
                System.out.println("Fatal error: " + exception.toString());
              }
            }
          });

          File xmlFile = new File(file);
          Document document = builder.parse(xmlFile);
          retval = SUCCESS;

        } catch (ParserConfigurationException | SAXException | IOException e) {
          allCorrect = false;
          if (!quietMode) {
            System.out.println("Validation error: " + e.toString());
          }
          retval = ERROR_VALIDATION;
        }
      }
      if (passthroughJustify) {
        System.err.println(String.format("NOT validating (passthrough) '%s' with justify (jakarta.json)...", file));
        JsonParser parser = null;
        try {
          parser = Json.createParser(new FileInputStream(file));
        } catch (FileNotFoundException e) {
          retval = ERROR_FILEIO;
          System.out.println(e.getLocalizedMessage());
        }
        if (parser == null) {
          retval = ERROR_NULL;
        } else {
          try {
            while (parser.hasNext()) {
              Event jevent = parser.next();
            }
          } catch (javax.json.stream.JsonParsingException e) {
            retval = ERROR_SYNTAX;
            System.out.println(e.getLocalizedMessage());
          }
        }
      }
      if (passthroughEverit) {
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
      }
    }
    if (validateJustify || validateEverit || (validateXml && matchingDtdProvided)) {
      if (allCorrect) {
        System.err.println("No validation issues encountered.");
      } else {
        System.err.println("At least one validation issue encountered.");
      }
    }
    if ((retval == SUCCESS) && !allCorrect) {
      retval = ERROR_VALIDATION;
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
    String cls = (new Exception().getStackTrace())[0].getClassName();
    try {
      Class<?> z = Class.forName(cls);
      String p = z.getResource(z.getSimpleName() + ".class").toString();
      if (p.startsWith("jar")) {
        String mp = p.substring(0, p.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
        Manifest m = new Manifest(new URL(mp).openStream());
        Attributes a = m.getMainAttributes();
        String val = a.getValue(key);
        if (val != null) {
          attr = val;
        }
      }
    } catch (Exception e) {
      // don't care
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
        case "-vx": jjval.setValidateXml(true); break;
        case "-pj": jjval.setPassthroughJustify(true); break;
        case "-pe": jjval.setPassthroughEverit(true); break;
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

