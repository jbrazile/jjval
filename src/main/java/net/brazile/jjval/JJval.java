/**
 * Java JSON schema based validator. This is a simple driver for several JSON
 * schema validators (justify, everit, networknt, json-sKema, jsonschemafriend)
 * plus DTD and XSD based XML validation, packaged as a standalone jar.
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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import jakarta.json.Json;
import jakarta.json.JsonException;

import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.leadpony.justify.api.JsonSchemaReader;
import org.leadpony.justify.api.JsonSchemaReaderFactory;
import org.leadpony.justify.api.JsonValidationService;
import org.leadpony.justify.api.ProblemHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Java-based validator for JSON, YAML and XML documents, optionally using JSON-Schema.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 - success: no syntax and no validation problems</li>
 *   <li>1 - syntax error in an input document</li>
 *   <li>2 - at least one schema/DTD/XSD validation problem</li>
 *   <li>3 - the supplied schema could not be parsed or understood</li>
 *   <li>4 - an input file could not be read</li>
 *   <li>5 - command line usage error</li>
 * </ul>
 */
public class JJval {
  static final int SUCCESS          = 0;
  static final int ERROR_SYNTAX     = 1;
  static final int ERROR_VALIDATION = 2;
  static final int ERROR_SCHEMA     = 3;
  static final int ERROR_FILEIO     = 4;
  static final int ERROR_USAGE      = 5;

  private static final String PROGRAM         = "jjval";
  private static final String BUILD_TIME_ATTR = "Build-Time";
  private static final String VERSION_ATTR    = "Implementation-Version";
  private static final String UNKNOWN         = "(unknown)";
  private static final String READ_ERROR      = "Error reading input file: ";
  private static final String SCHEMA_KEYWORD  = "$schema";

  // -------------------------------------------------------------------------
  // Dialect (--draft)
  // -------------------------------------------------------------------------

  /**
   * A JSON Schema dialect that can be pinned on the command line with --draft.
   */
  public enum Draft {
    D04  ("04",   "http://json-schema.org/draft-04/schema#"),
    D06  ("06",   "http://json-schema.org/draft-06/schema#"),
    D07  ("07",   "http://json-schema.org/draft-07/schema#"),
    D2019("2019", "https://json-schema.org/draft/2019-09/schema"),
    D2020("2020", "https://json-schema.org/draft/2020-12/schema");

    private final String label;
    private final String metaSchemaUri;

    Draft(String label, String metaSchemaUri) {
      this.label = label;
      this.metaSchemaUri = metaSchemaUri;
    }

    public String label() { return label; }
    public String metaSchemaUri() { return metaSchemaUri; }

    /** Parse a --draft value, accepting a few common spellings. */
    static Draft fromLabel(String value) {
      String v = value.trim().toLowerCase()
          .replace("draft", "").replace("-", "").replace("_", "");
      switch (v) {
        case "4":  case "04":        return D04;
        case "6":  case "06":        return D06;
        case "7":  case "07":        return D07;
        case "2019": case "201909":  return D2019;
        case "2020": case "202012":  return D2020;
        default:                     return null;
      }
    }

    static String labels() {
      StringBuilder sb = new StringBuilder();
      for (Draft d : values()) {
        sb.append(sb.length() == 0 ? "" : "|").append(d.label);
      }
      return sb.toString();
    }
  }

  // -------------------------------------------------------------------------
  // Mode
  // -------------------------------------------------------------------------

  /** The single operating mode selected on the command line. */
  public enum Mode {
    VJ("-vj", "validate json with justify (jakarta.json)",               true,  false,
        EnumSet.of(Draft.D04, Draft.D06, Draft.D07)),
    VE("-ve", "validate json with everit (org.json)",                    true,  false,
        EnumSet.of(Draft.D04, Draft.D06, Draft.D07)),
    VN("-vn", "validate json with networknt (jackson)",                  true,  false,
        EnumSet.allOf(Draft.class)),
    VK("-vk", "validate json with json-sKema (draft 2020-12 only)",      true,  false,
        EnumSet.of(Draft.D2020)),
    VF("-vf", "validate json with jsonschemafriend",                     true,  false,
        EnumSet.allOf(Draft.class)),
    VY("-vy", "validate yaml with networknt (jackson-dataformat-yaml)",  true,  false,
        EnumSet.allOf(Draft.class)),
    VX("-vx", "validate xml against a dtd given with -d",                false, true,
        EnumSet.noneOf(Draft.class)),
    VS("-vs", "validate xml against a w3c xsd given with -s",            true,  false,
        EnumSet.noneOf(Draft.class)),
    PJ("-pj", "parse only (passthrough) with justify (jakarta.json)",    false, false,
        EnumSet.noneOf(Draft.class)),
    PE("-pe", "parse only (passthrough) with everit (org.json)",         false, false,
        EnumSet.noneOf(Draft.class)),
    PN("-pn", "parse only (passthrough) with networknt (jackson)",       false, false,
        EnumSet.noneOf(Draft.class));

    private final String flag;
    private final String description;
    private final boolean requiresSchema;
    private final boolean dtd;
    private final Set<Draft> supportedDrafts;

    Mode(String flag, String description,
         boolean requiresSchema, boolean dtd, Set<Draft> supportedDrafts) {
      this.flag = flag;
      this.description = description;
      this.requiresSchema = requiresSchema;
      this.dtd = dtd;
      this.supportedDrafts = supportedDrafts;
    }

    public String flag() { return flag; }

    /** Look up a mode by its command-line flag, e.g. "-vj". */
    static Mode fromFlag(String flag) {
      for (Mode mode : values()) {
        if (mode.flag.equals(flag)) return mode;
      }
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Instance state
  // -------------------------------------------------------------------------

  private boolean allCorrect          = true;
  private boolean quietMode           = false;
  private boolean showVersion         = true;
  private boolean helpRequested       = false;
  private boolean matchingDtdProvided = false;
  private String  schemaFile          = null;
  private String  xmlDtd              = null;
  private Mode    mode                = null;
  private Draft   draft               = null;
  private List<String> files          = new ArrayList<>();

  // engine state (only the field for the selected mode is populated)
  private JsonValidationService jService                       = null;
  private org.leadpony.justify.api.JsonSchema jSchema          = null;
  private com.networknt.schema.JsonSchema nSchema              = null;
  private org.everit.json.schema.Schema eSchema                = null;
  private com.github.erosb.jsonsKema.Validator kValidator      = null;
  private net.jimblackler.jsonschemafriend.Schema fSchema      = null;
  private net.jimblackler.jsonschemafriend.Validator fValidator = null;
  private javax.xml.validation.Schema xsdSchema                = null;

  // -------------------------------------------------------------------------
  // Setters
  // -------------------------------------------------------------------------

  public void setMode(Mode mode)           { this.mode = mode; }
  public Mode getMode()                    { return mode; }
  public void setDraft(Draft draft)        { this.draft = draft; }
  public void setQuietMode(boolean f)      { this.quietMode = f; }
  public void setShowVersion(boolean f)    { this.showVersion = f; }
  public void setSchemaFile(String s)      { this.schemaFile = s; }
  public void setXmlDtdFile(String s)      { this.xmlDtd = s; }
  public void setFiles(List<String> files) { this.files = new ArrayList<>(files); }

  // -------------------------------------------------------------------------
  // Output helpers
  // -------------------------------------------------------------------------

  private void report(String message) {
    if (!quietMode) System.out.println(message);
  }

  private void reportAll(Set<String> messages) {
    for (String m : messages) report(m);
  }

  private void status(String message) {
    if (!quietMode) System.err.println(message);
  }

  // -------------------------------------------------------------------------
  // Usage
  // -------------------------------------------------------------------------

  static int usage(String msg) {
    PrintStream out = (msg == null) ? System.out : System.err;
    if (msg != null) out.println(msg);
    out.println(String.format(
        "usage: %s <mode> [-s schema] [-d dtd] [--draft %s] [-nv] [-q] file...",
        PROGRAM, Draft.labels()));
    out.println("  exactly one mode must be given:");
    for (Mode m : Mode.values()) {
      out.println(String.format("    %s\t\t%s", m.flag(), m.description));
    }
    out.println("  options:");
    out.println("    -s (schema)\tJSON schema (or .xsd for -vs) to validate against");
    out.println("    -d (dtd)\tDTD file to validate against (used by -vx)");
    out.println(String.format("    --draft (%s)", Draft.labels()));
    out.println("\t\tdialect to assume when the schema has no $schema keyword");
    out.println("    -nv\t\tdon't show version");
    out.println("    -q\t\tquiet mode - no output, run only for the exit code");
    out.println("    -h\t\tshow this help");
    out.println("  exit: 0=ok 1=syntax error 2=validation error 3=bad schema 4=file i/o 5=usage");
    return (msg == null) ? SUCCESS : ERROR_USAGE;
  }

  // -------------------------------------------------------------------------
  // Main entry point
  // -------------------------------------------------------------------------

  public int validate() {
    int retval = checkArguments();
    if (retval != SUCCESS) return retval;

    if (showVersion && !quietMode) {
      System.err.println(String.format("%s (version: %s  build: %s)",
          PROGRAM, getJarAttr(VERSION_ATTR), getJarAttr(BUILD_TIME_ATTR)));
    }

    retval = setupValidators();
    if (retval != SUCCESS) return retval;

    PrintingProblemHandler handler = new PrintingProblemHandler();
    for (String file : files) {
      int r = processFile(file, handler);
      if (retval == SUCCESS) retval = r;
    }

    // summary only when something was actually validated
    if (mode.requiresSchema || (mode.dtd && matchingDtdProvided)) {
      if (!allCorrect) {
        status("At least one validation issue encountered.");
      } else if (retval == SUCCESS) {
        status("No validation issues encountered.");
      } else {
        status("Validation incomplete - at least one document could not be processed.");
      }
      if ((retval == SUCCESS) && !allCorrect) retval = ERROR_VALIDATION;
    }
    return retval;
  }

  private int processFile(String file, PrintingProblemHandler handler) {
    Path path;
    try {
      path = Paths.get(file);
    } catch (InvalidPathException e) {
      System.err.println(String.format("Cannot read '%s': %s", file, e.getMessage()));
      return ERROR_FILEIO;
    }
    if (!Files.isReadable(path)) {
      System.err.println(String.format("Cannot read '%s'", file));
      return ERROR_FILEIO;
    }
    switch (mode) {
      case VJ:  return processJustifyValidation(file, path, handler);
      case VE:  return processEveritValidation(file, path);
      case VN:  return processNetworkntValidation(file, path, false);
      case VY:  return processNetworkntValidation(file, path, true);
      case VK:  return processSkemaValidation(file, path);
      case VF:  return processFriendValidation(file, path);
      case VX:  return processDtdValidation(file);
      case VS:  return processXsdValidation(file);
      case PJ:  return processJustifyPassthrough(file, path);
      case PE:  return processEveritPassthrough(file, path);
      case PN:  return processNetworkntPassthrough(file, path);
      default:  return ERROR_USAGE;
    }
  }

  // -------------------------------------------------------------------------
  // Argument validation
  // -------------------------------------------------------------------------

  private int checkArguments() {
    if (mode == null) {
      StringBuilder flags = new StringBuilder();
      for (Mode m : Mode.values()) {
        flags.append(flags.length() == 0 ? "" : ", ").append(m.flag());
      }
      return usage("Exactly one of " + flags + " must be specified");
    }
    if (mode.requiresSchema && ((schemaFile == null) || !(new File(schemaFile)).canRead())) {
      return usage(String.format(
          "with %s, a readable schema file must be specified with -s", mode.flag()));
    }
    if (draft != null && !mode.supportedDrafts.contains(draft)) {
      if (mode.supportedDrafts.isEmpty()) {
        return usage(String.format("%s does not support --draft", mode.flag()));
      }
      StringBuilder supported = new StringBuilder();
      for (Draft d : mode.supportedDrafts) {
        supported.append(supported.length() == 0 ? "" : ", ").append(d.label());
      }
      return usage(String.format("%s does not support --draft %s (supported: %s)",
          mode.flag(), draft.label(), supported));
    }
    if (files.isEmpty()) {
      return usage("At least one file to validate must be specified");
    }
    return SUCCESS;
  }

  // -------------------------------------------------------------------------
  // Schema setup
  // -------------------------------------------------------------------------

  private int setupValidators() {
    if (!mode.requiresSchema) return SUCCESS;
    try {
      switch (mode) {
        case VJ:           setupJustify();    break;
        case VE:           setupEverit();     break;
        case VN: case VY:  setupNetworknt();  break;
        case VK:           setupSkema();      break;
        case VF:           setupFriend();     break;
        case VS:           setupXsd();        break;
        default: break;
      }
    } catch (IOException e) {
      System.err.println(String.format(
          "Error reading schema '%s': %s", schemaFile, e.getMessage()));
      return ERROR_FILEIO;
    } catch (SAXException e) {
      System.err.println(String.format(
          "Error parsing schema '%s': %s", schemaFile, e.getMessage()));
      return ERROR_SCHEMA;
    } catch (Exception e) {
      System.err.println(String.format(
          "Error parsing schema '%s': %s", schemaFile, e.getMessage()));
      return ERROR_SCHEMA;
    }
    return SUCCESS;
  }

  private void setupJustify() throws IOException {
    jService = JsonValidationService.newInstance();
    JsonSchemaReaderFactory factory = jService;
    if (draft != null) {
      factory = jService.createSchemaReaderFactoryBuilder()
          .withDefaultSpecVersion(
              org.leadpony.justify.api.SpecVersion.valueOf("DRAFT_" + draft.label()))
          .build();
    }
    try (InputStream s = Files.newInputStream(Paths.get(schemaFile));
         JsonSchemaReader reader = factory.createSchemaReader(s)) {
      jSchema = reader.read();
    }
  }

  private void setupEverit() throws IOException {
    try (InputStream s = Files.newInputStream(Paths.get(schemaFile))) {
      JSONObject schemaJson = new JSONObject(new JSONTokener(s));
      SchemaLoader.SchemaLoaderBuilder builder = SchemaLoader.builder().schemaJson(schemaJson);
      if (draft == Draft.D06) {
        builder.draftV6Support();
      } else if (draft == Draft.D07) {
        builder.draftV7Support();
      }
      eSchema = builder.build().load().build();
    }
  }

  private void setupNetworknt() throws IOException {
    SpecVersion.VersionFlag version = SpecVersion.VersionFlag.V202012;
    if (draft != null) {
      switch (draft) {
        case D04:   version = SpecVersion.VersionFlag.V4;      break;
        case D06:   version = SpecVersion.VersionFlag.V6;      break;
        case D07:   version = SpecVersion.VersionFlag.V7;      break;
        case D2019: version = SpecVersion.VersionFlag.V201909; break;
        default:    version = SpecVersion.VersionFlag.V202012; break;
      }
    }
    try (InputStream s = Files.newInputStream(Paths.get(schemaFile))) {
      nSchema = JsonSchemaFactory.getInstance(version).getSchema(s);
    }
  }

  private void setupSkema() throws IOException {
    try (InputStream s = Files.newInputStream(Paths.get(schemaFile))) {
      com.github.erosb.jsonsKema.Schema kSchema =
          new com.github.erosb.jsonsKema.SchemaLoader(
              new com.github.erosb.jsonsKema.JsonParser(s).parse()).load();
      kValidator = com.github.erosb.jsonsKema.Validator.forSchema(kSchema);
    }
  }

  private void setupFriend()
      throws IOException, net.jimblackler.jsonschemafriend.GenerationException {
    net.jimblackler.jsonschemafriend.SchemaStore store =
        new net.jimblackler.jsonschemafriend.SchemaStore();
    if (draft == null) {
      fSchema = store.loadSchema(new File(schemaFile));
    } else {
      // inject a $schema keyword so that jsonschemafriend uses the requested dialect
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> schemaJson = mapper.readValue(new File(schemaFile),
          mapper.getTypeFactory().constructMapType(
              LinkedHashMap.class, String.class, Object.class));
      if (!schemaJson.containsKey(SCHEMA_KEYWORD)) {
        Map<String, Object> pinned = new LinkedHashMap<>();
        pinned.put(SCHEMA_KEYWORD, draft.metaSchemaUri());
        pinned.putAll(schemaJson);
        schemaJson = pinned;
      }
      fSchema = store.loadSchema(schemaJson);
    }
    fValidator = new net.jimblackler.jsonschemafriend.Validator(false);
  }

  private void setupXsd() throws SAXException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,    "file");
    } catch (SAXException | IllegalArgumentException e) {
      // not supported by this parser - carry on without it
    }
    xsdSchema = factory.newSchema(new File(schemaFile));
  }

  // -------------------------------------------------------------------------
  // Validation engines
  // -------------------------------------------------------------------------

  /** justify validation. */
  private int processJustifyValidation(String file, Path path, PrintingProblemHandler handler) {
    status(String.format("Validating '%s' with justify...", file));
    try (InputStream s = Files.newInputStream(path);
         jakarta.json.stream.JsonParser p = jService.createParser(s, jSchema, handler)) {
      while (p.hasNext()) p.next();
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage());
      return ERROR_FILEIO;
    } catch (JsonException e) {
      report(e.getLocalizedMessage());
      return ERROR_SYNTAX;
    }
    return SUCCESS;
  }

  /** everit validation. */
  private int processEveritValidation(String file, Path path) {
    status(String.format("Validating '%s' with everit...", file));
    Object document;
    try (InputStream s = Files.newInputStream(path)) {
      document = readJsonDocument(s);
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage());
      return ERROR_FILEIO;
    } catch (JSONException e) {
      report(e.getLocalizedMessage());
      return ERROR_SYNTAX;
    }
    try {
      eSchema.validate(document);
    } catch (ValidationException e) {
      allCorrect = false;
      report(e.toJSON().toString(2));
    }
    return SUCCESS;
  }

  /** networknt validation (JSON or YAML). */
  private int processNetworkntValidation(String file, Path path, boolean yaml) {
    status(String.format("Validating '%s' with networknt (%s)...", file, yaml ? "yaml" : "json"));
    ObjectMapper mapper = yaml ? new ObjectMapper(new YAMLFactory()) : new ObjectMapper();
    try (InputStream s = Files.newInputStream(path)) {
      JsonNode jsonNode = mapper.readTree(s);
      Set<ValidationMessage> msgs = nSchema.validate(jsonNode);
      if (!msgs.isEmpty()) {
        allCorrect = false;
        Set<String> sorted = new TreeSet<>();
        for (ValidationMessage m : msgs) sorted.add(m.getMessage());
        reportAll(sorted);
      }
    } catch (JsonParseException e) {
      report(syntaxMessage(e));
      return ERROR_SYNTAX;
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  /** json-sKema validation. */
  private int processSkemaValidation(String file, Path path) {
    status(String.format("Validating '%s' with json-sKema...", file));
    try (InputStream s = Files.newInputStream(path)) {
      com.github.erosb.jsonsKema.ValidationFailure failure =
          kValidator.validate(new com.github.erosb.jsonsKema.JsonParser(s).parse());
      if (failure != null) {
        allCorrect = false;
        Set<String> sorted = new TreeSet<>();
        for (com.github.erosb.jsonsKema.ValidationFailure f : failure.flatten()) {
          sorted.add(String.format("[%s] %s", pointerOf(f.getDynamicPath()), f.getMessage()));
        }
        reportAll(sorted);
      }
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage());
      return ERROR_FILEIO;
    } catch (com.github.erosb.jsonsKema.JsonParseException e) {
      report(e.getMessage());
      return ERROR_SYNTAX;
    }
    return SUCCESS;
  }

  /**
   * Strip the document URI from a json-sKema dynamic path so the output is
   * stable regardless of where the file lives on disk.
   */
  private static String pointerOf(Object dynamicPath) {
    String path = String.valueOf(dynamicPath);
    int hash = path.indexOf('#');
    return (hash < 0) ? path : path.substring(hash);
  }

  /** jsonschemafriend validation. */
  private int processFriendValidation(String file, Path path) {
    status(String.format("Validating '%s' with jsonschemafriend...", file));
    Object document;
    try (InputStream s = Files.newInputStream(path)) {
      document = new ObjectMapper().readValue(s, Object.class);
    } catch (JsonParseException e) {
      report(syntaxMessage(e));
      return ERROR_SYNTAX;
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage());
      return ERROR_FILEIO;
    }
    Set<String> msgs = new TreeSet<>();
    fValidator.validate(fSchema, document, URI.create(""),
        err -> msgs.add(String.format("[%s] %s", err.getUri(), err.getMessage())));
    if (!msgs.isEmpty()) {
      allCorrect = false;
      reportAll(msgs);
    }
    return SUCCESS;
  }

  /** DTD-based XML validation. */
  private int processDtdValidation(String file) {
    try {
      DocumentBuilder builder = newValidatingDocumentBuilder();
      builder.setEntityResolver((publicId, systemId) -> resolveDtd(file, systemId));
      builder.setErrorHandler(new ReportingErrorHandler());
      builder.parse(new File(file));
    } catch (ParserConfigurationException e) {
      System.err.println("XML parser configuration error: " + e.getMessage());
      return ERROR_SCHEMA;
    } catch (SAXParseException e) {
      return ERROR_SYNTAX;   // fatal well-formedness error, already reported
    } catch (SAXException e) {
      allCorrect = false;
      report("Validation error: " + e);
      return ERROR_VALIDATION;
    } catch (IOException e) {
      System.err.println("Error reading XML file: " + e.getMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  /** W3C XSD-based XML validation. */
  private int processXsdValidation(String file) {
    status(String.format("Validating '%s' with xsd '%s'...", file, schemaFile));
    javax.xml.validation.Validator validator = xsdSchema.newValidator();
    validator.setErrorHandler(new ReportingErrorHandler());
    try {
      validator.validate(new StreamSource(new File(file)));
    } catch (SAXParseException e) {
      return ERROR_SYNTAX;   // fatal well-formedness error, already reported
    } catch (SAXException e) {
      allCorrect = false;
      report("Validation error: " + e);
      return ERROR_VALIDATION;
    } catch (IOException e) {
      System.err.println("Error reading XML file: " + e.getMessage());
      return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  private static DocumentBuilder newValidatingDocumentBuilder()
      throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(true);
    factory.setNamespaceAware(true);
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "file");
    } catch (ParserConfigurationException | IllegalArgumentException e) {
      // not supported - carry on
    }
    return factory.newDocumentBuilder();
  }

  private InputSource resolveDtd(String file, String systemId) {
    if (xmlDtd != null && !xmlDtd.isEmpty() && systemId != null) {
      String dtdName = new File(xmlDtd).getName();
      if (systemId.endsWith(dtdName)) {
        status(String.format("Validating '%s' with dtd '%s'...", file, xmlDtd));
        matchingDtdProvided = true;
        return new InputSource(new File(xmlDtd).toURI().toString());
      }
      status(String.format(
          "NOT Validating (passthrough) '%s' (expected dtd='%s' but provided dtd='%s')...",
          file, new File(systemId).getName(), dtdName));
      return null;
    }
    status(String.format("NOT Validating (passthrough) '%s' with jdk..", file));
    return null;
  }

  private class ReportingErrorHandler implements ErrorHandler {
    @Override public void warning(SAXParseException e) {
      allCorrect = false;
      report("Warning: " + e);
    }
    @Override public void error(SAXParseException e) {
      allCorrect = false;
      report("Error: " + e.getMessage());
    }
    @Override public void fatalError(SAXParseException e) throws SAXParseException {
      allCorrect = false;
      report("Fatal error: " + e.getMessage());
      throw e;
    }
  }

  // -------------------------------------------------------------------------
  // Passthrough (parse-only) engines
  // -------------------------------------------------------------------------

  private int processJustifyPassthrough(String file, Path path) {
    status(String.format(
        "NOT validating (passthrough) '%s' with justify (jakarta.json)...", file));
    try (InputStream s = Files.newInputStream(path);
         jakarta.json.stream.JsonParser p = Json.createParser(s)) {
      while (p.hasNext()) p.next();
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage()); return ERROR_FILEIO;
    } catch (JsonException e) {
      report(e.getLocalizedMessage()); return ERROR_SYNTAX;
    }
    return SUCCESS;
  }

  private int processEveritPassthrough(String file, Path path) {
    status(String.format(
        "NOT validating (passthrough) '%s' with everit (org.json)...", file));
    try (InputStream s = Files.newInputStream(path)) {
      readJsonDocument(s);
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage()); return ERROR_FILEIO;
    } catch (JSONException e) {
      report(e.getLocalizedMessage()); return ERROR_SYNTAX;
    }
    return SUCCESS;
  }

  private int processNetworkntPassthrough(String file, Path path) {
    status(String.format(
        "NOT validating (passthrough) '%s' with networknt (jackson)...", file));
    try (InputStream s = Files.newInputStream(path);
         com.fasterxml.jackson.core.JsonParser p = new JsonFactory().createParser(s)) {
      while (p.nextToken() != null) { /* parse quietly */ }
    } catch (JsonParseException e) {
      report(syntaxMessage(e)); return ERROR_SYNTAX;
    } catch (IOException e) {
      System.err.println(READ_ERROR + e.getMessage()); return ERROR_FILEIO;
    }
    return SUCCESS;
  }

  // -------------------------------------------------------------------------
  // Utilities
  // -------------------------------------------------------------------------

  /** Fully parse a JSON document with org.json, rejecting trailing content. */
  private static Object readJsonDocument(InputStream jsonStream) {
    JSONTokener tokener = new JSONTokener(jsonStream);
    Object document = tokener.nextValue();
    if (tokener.nextClean() != 0) {
      throw tokener.syntaxError(
          "Unexpected content after the end of the JSON document");
    }
    return document;
  }

  /** Render a jackson parse exception as a stable, compact one-liner. */
  private static String syntaxMessage(JsonParseException e) {
    JsonLocation loc = e.getLocation();
    if (loc == null) return e.getOriginalMessage();
    return String.format("%s at (line no=%d, column no=%d)",
        e.getOriginalMessage(), loc.getLineNr(), loc.getColumnNr());
  }

  private static String getJarAttr(String key) {
    String attr = UNKNOWN;
    try (InputStream s = JJval.class.getClassLoader()
        .getResourceAsStream("META-INF/MANIFEST.MF")) {
      if (s != null) {
        Attributes attributes = new Manifest(s).getMainAttributes();
        String value = attributes.getValue(key);
        if (value != null) attr = value;
      }
    } catch (IOException e) {
      System.err.println("Error reading manifest attribute: " + e.getMessage());
    }
    return attr;
  }

  /** Printing problem handler for the justify engine. */
  private class PrintingProblemHandler implements ProblemHandler {
    @Override
    public void handleProblems(List<org.leadpony.justify.api.Problem> problems) {
      for (org.leadpony.justify.api.Problem p : problems) {
        allCorrect = false;
        report(p.toString());
      }
    }
  }

  // -------------------------------------------------------------------------
  // Command-line parsing
  // -------------------------------------------------------------------------

  static int parseArguments(String[] args, JJval jjval) {
    List<String> filesToValidate = new ArrayList<>();
    int i = 0;
    while (i < args.length) {
      String arg = args[i++];
      Mode argMode = Mode.fromFlag(arg);
      if (argMode != null) {
        if (jjval.getMode() != null && jjval.getMode() != argMode) {
          return usage(String.format("Only one mode may be given (found %s and %s)",
              jjval.getMode().flag(), argMode.flag()));
        }
        jjval.setMode(argMode);
      } else if ("-s".equals(arg) || "-d".equals(arg) || "--draft".equals(arg)) {
        if (i >= args.length) {
          return usage(String.format("Missing argument for %s", arg));
        }
        String value = args[i++];
        if      ("-s".equals(arg))       { jjval.setSchemaFile(value); }
        else if ("-d".equals(arg))       { jjval.setXmlDtdFile(value); }
        else {
          Draft argDraft = Draft.fromLabel(value);
          if (argDraft == null) {
            return usage(String.format(
                "Unknown --draft '%s' (expected one of %s)", value, Draft.labels()));
          }
          jjval.setDraft(argDraft);
        }
      } else if ("-nv".equals(arg)) {
        jjval.setShowVersion(false);
      } else if ("-q".equals(arg)) {
        jjval.setQuietMode(true);
      } else if ("-h".equals(arg) || "-help".equals(arg) || "--help".equals(arg)) {
        jjval.helpRequested = true;
        return usage(null);
      } else if (arg.startsWith("-") && arg.length() > 1) {
        return usage(String.format("Unknown option '%s'", arg));
      } else {
        filesToValidate.add(arg);
      }
    }
    jjval.setFiles(filesToValidate);
    return SUCCESS;
  }

  public static void main(String[] args) {
    JJval jjval = new JJval();
    int retval = parseArguments(args, jjval);
    if (retval == SUCCESS && !jjval.helpRequested) {
      retval = jjval.validate();
    }
    System.exit(retval);
  }
}

