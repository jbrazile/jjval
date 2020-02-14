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
import java.lang.invoke.MethodHandles;
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
import org.json.JSONObject;
import org.json.JSONTokener;
import org.leadpony.justify.api.JsonSchema;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;

public class JJval {
  public static final String VERSION = "v1.0.3";
  public boolean allCorrect          = true;
  public boolean quietMode           = false;

  class PrintingProblemHandler implements ProblemHandler {
    public void handleProblems(List<org.leadpony.justify.api.Problem> problems) {
      for(org.leadpony.justify.api.Problem problem : problems) {
        allCorrect = false;
        if (!quietMode) { System.out.println(problem.toString()); }
      }
    }
  }

  public static void usage(String msg) {
    System.out.println(String.format("%s\nusage: %s [-vj][-ve] -s [schema] file...", msg, "jjval"));
    System.out.println(String.format("(version: %s)", VERSION));
    System.out.println("    -vj\t\tvalidate with justify");
    System.out.println("    -ve\t\tvalidate with everit");
    System.out.println("    -pj\t\tpassthrough with justify (jakarta.json)");
    System.out.println("    -pe\t\tpassthrough with everit (org.json)");
    System.out.println("    -s (schema)\tJSON schema for validation purposes");
    System.out.println("    -q\t\tquiet mode - no validation output, run only for exit code");
    System.exit(-1);
  }

  public static void main(String[] args) throws Exception {
    JJval jjval = new JJval();
    System.exit(jjval.validate(args) ? 0 : -1);
  }

  public boolean validate(String[] args) throws Exception {
    String jsonSchema              = null;;
    boolean validateJustify        = false;
    boolean validateEverit         = false;
    boolean passthroughJustify     = false;
    boolean passthroughEverit      = false;
    List<String> files             = new ArrayList<>();
    JsonValidationService jService = null;
    JsonSchema jSchema             = null;
    Schema eSchema                 = null;

    // parse command line
    int state = 0;
    for (String arg: args) {
      switch(arg) {
        case "-vj": validateJustify=true; break;
        case "-ve": validateEverit=true; break;
        case "-pj": passthroughJustify=true; break;
        case "-pe": passthroughEverit=true; break;
        case "-q":  quietMode=true; break;
        case "-s":  state = 1; break;
        default:
          if (state == 1) {
            jsonSchema = arg; state = 0;
          } else {
            files.add(arg);
          }
          break;
      }
    }
    // validate command line arguments
    if (!validateJustify && !validateEverit && !passthroughJustify && !passthroughEverit) { usage("At least one of -vj, -ve, -pj, -pe must be specified");}
    if ((validateJustify || validateEverit) && ((jsonSchema == null) || !(new File(jsonSchema)).canRead())) {usage("with -vj, -ve, a readable schema file must be specified with -s");}
    if (files.size() < 1) {usage("At least one file to validate must be specified");}

    // setup validator(s)
    if (validateJustify) {
      jService = JsonValidationService.newInstance();
      jSchema = jService.readSchema(Paths.get(jsonSchema));
    }
    if (validateEverit) {
      eSchema = SchemaLoader.load(new JSONObject(new String(Files.readAllBytes(Paths.get(jsonSchema)))));
    }

    // process all given files
    PrintingProblemHandler handler = new PrintingProblemHandler();
    for (String file: files) {
      if (validateJustify) {
        System.out.println(String.format("Validating '%s' with justify...", file));
        JsonParser jParser = jService.createParser(Paths.get(file), jSchema, handler);
        while(jParser.hasNext()) { Event jevent = jParser.next(); }
      }
      if (validateEverit) {
        System.out.println(String.format("Validating '%s' with everit...", file));
        try {
          eSchema.validate(new org.json.JSONObject(new String(Files.readAllBytes(Paths.get(file)))));
        } catch (ValidationException e) {
          allCorrect = false;
          if (!quietMode) {System.out.println(e.toJSON());}
        }
      }
      if (passthroughJustify) {
        System.out.println(String.format("NOT validating (passthrough) '%s' with justify (jakarta.json)...", file));
        JsonParser parser = Json.createParser(new FileInputStream(file));
        while(parser.hasNext()) { Event jevent = parser.next(); }
      }
      if (passthroughEverit) {
        System.out.println(String.format("NOT validating (passthrough) '%s' with everit (org.json)...", file));
        JSONTokener tokener = new JSONTokener(new FileInputStream(file));
        while(tokener.more()) { tokener.next(); }
      }
    }
    if (validateJustify || validateEverit) {
      if (allCorrect) {
        System.out.println("No validation issues encountered.");
      } else {
        System.out.println("At least one validation issue encountered.");
      }
    }
    return allCorrect;
  }
}

