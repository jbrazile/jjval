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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.json.Json;
import javax.json.stream.JsonParser.Event;
import javax.json.stream.JsonParser;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.leadpony.justify.api.JsonSchema;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;

/**
 * Java-based JSON validator optionally using JSON-Schema.
 */
public class JJval {
  private static final String VERSION       = "v1.0.4";
  private static final int SUCCESS          = 0;
  private static final int ERROR_SYNTAX     = 1;
  private static final int ERROR_VALIDATION = 2;
  private static final int ERROR_NULL       = 3;
  private static final int ERROR_FILEIO     = 4;
  private static final int ERROR_USAGE      = 5;

  private boolean allCorrect                = true;
  private boolean quietMode                 = false;
  private String jsonSchema                 = null;;
  private boolean validateJustify           = false;
  private boolean validateEverit            = false;
  private boolean passthroughJustify        = false;
  private boolean passthroughEverit         = false;
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
  public void setPassthroughJustify(boolean flag) {
    this.passthroughJustify = flag;
  }
  public void setPassthroughEverit(boolean flag) {
    this.passthroughEverit = flag;
  }
  public void setQuietMode(boolean flag) {
    this.quietMode = flag;
  }
  public void setJsonSchemaFile(String jsonSchemaFile) {
    this.jsonSchema = jsonSchemaFile;
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
    System.err.println(String.format("(version: %s)", VERSION));
    System.err.println("    -vj\t\tvalidate with justify");
    System.err.println("    -ve\t\tvalidate with everit");
    System.err.println("    -pj\t\tpassthrough with justify (jakarta.json)");
    System.err.println("    -pe\t\tpassthrough with everit (org.json)");
    System.err.println("    -s (schema)\tJSON schema for validation purposes");
    System.err.println("    -q\t\tquiet mode - no validation output, run only for exit code");
    System.exit(ERROR_USAGE);
  }

  /**
   * Validate a JSON file optionally against a JSON schema with either the everit (org.json) or justify (jakarta.json) validation engines.
   * @param args command line arguments passed through.
   * @return integer result to use as program return value.
   */
  public int validate(String[] args) {
    int retval = SUCCESS;

    // validate arguments
    if (!validateJustify && !validateEverit && !passthroughJustify && !passthroughEverit) { usage("At least one of -vj, -ve, -pj, -pe must be specified");}
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
        e.printStackTrace();
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
              e.printStackTrace();
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
      if (passthroughJustify) {
        System.err.println(String.format("NOT validating (passthrough) '%s' with justify (jakarta.json)...", file));
        JsonParser parser = null;
        try {
          parser = Json.createParser(new FileInputStream(file));
        } catch (FileNotFoundException e) {
          retval = ERROR_FILEIO;
          e.printStackTrace();
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
            e.printStackTrace();
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
          e.printStackTrace();
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
            e.printStackTrace();
          }
        }
      }
    }
    if (validateJustify || validateEverit) {
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
        case "-pj": jjval.setPassthroughJustify(true); break;
        case "-pe": jjval.setPassthroughEverit(true); break;
        case "-q":  jjval.setQuietMode(true); break;
        case "-s":  state = 1; break;
        default:
          if (state == 1) {
            jjval.setJsonSchemaFile(arg); state = 0;
          } else {
            filesToValidate.add(arg);
          }
          break;
      }
    }
    jjval.setFiles(filesToValidate);
    System.exit(jjval.validate(args));
  }
}

