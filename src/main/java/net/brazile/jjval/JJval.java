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
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.json.stream.JsonParser.Event;
import javax.json.stream.JsonParser;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.leadpony.justify.api.JsonSchema;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;

public class JJval {
  class PrintingProblemHandler implements ProblemHandler {
    public void handleProblems(List<org.leadpony.justify.api.Problem> problems) {
      for(org.leadpony.justify.api.Problem problem : problems) {
        System.out.println(problem.toString());
      }
    }
  }

  public static void usage(String msg) {
    System.out.println(String.format("%s\nusage: %s [-vj][-ve] -s [schema] file...", msg, "jjval"));
    System.out.println("    -vj\t\tvalidate with justify");
    System.out.println("    -ve\t\tvalidate with everit");
    System.out.println("    -s (schema)\tJSON schema for validation purposes");
    System.exit(-1);
  }

  public static void main(String[] args) throws Exception {
    JJval jjval = new JJval();
    jjval.validate(args);
  }

  public void validate(String[] args) throws Exception {

    String jsonSchema = null;;
    boolean withJustify = false;
    boolean withEverit = false;
    List<String> files = new ArrayList<>();
    JsonValidationService jService = null;
    JsonSchema jSchema = null;
    Schema eSchema = null;

    // parse command line
    int state = 0;
    for (String arg: args) {
      switch(arg) {
        case "-vj": withJustify=true; break;
        case "-ve": withEverit=true; break;
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
    if (!withJustify && !withEverit) { usage("At least one of -vj or -ve needs to be specified");}
    if (jsonSchema == null || !(new File(jsonSchema)).canRead()) {usage("A readable schema file must be specified with -s");}
    if (files.size() < 1) {usage("At least one file to validate must be specified");}

    // setup validator(s)
    if (withJustify) {
      jService = JsonValidationService.newInstance();
      jSchema = jService.readSchema(Paths.get(jsonSchema));
    }
    if (withEverit) {
      eSchema = SchemaLoader.load(new JSONObject(new String(Files.readAllBytes(Paths.get(jsonSchema)))));
    }

    // validate all given files
    PrintingProblemHandler handler = new PrintingProblemHandler();
    for (String file: files) {
      if (withJustify) {
        JsonParser jParser = jService.createParser(Paths.get(file), jSchema, handler);
        while(jParser.hasNext()) { Event jevent = jParser.next(); }
      }
      if (withEverit) {
        try {
          eSchema.validate(new org.json.JSONObject(new String(Files.readAllBytes(Paths.get(file)))));
        } catch (ValidationException e) {
          System.out.println(e.toJSON());
        }
      }
    }
  }
}

