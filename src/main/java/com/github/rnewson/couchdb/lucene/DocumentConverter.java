package com.github.rnewson.couchdb.lucene;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import com.github.rnewson.couchdb.lucene.couchdb.Database;

public final class DocumentConverter {

    private static final Document[] NO_DOCUMENTS = new Document[0];
    private static final Logger LOG = Logger.getLogger(DocumentConverter.class);

    private final Context context;
    private final Function main;
    private final Function viewFun;
    private final ScriptableObject scope;

    public DocumentConverter(final Context context, final String functionName, final String function) throws IOException {
        this.context = context;
        scope = context.initStandardObjects();

        // Allow custom document helper class.
        try {
            ScriptableObject.defineClass(scope, RhinoDocument.class);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        // Add a log object
        ScriptableObject.putProperty(scope, "log", new JSLog());

        // Load JSON parser.
        context.evaluateString(scope, loadResource("json2.js"), "json2", 0, null);

        // Define outer function.
        main = context.compileFunction(scope, "function(json, func) { return func(JSON.parse(json)); }", "main", 0, null);

        // Compile user-specified function
        viewFun = context.compileFunction(scope, function, functionName, 0, null);
    }

    public Document[] convert(final JSONObject doc, final JSONObject defaults, final Database database) throws IOException {
        final Object result;
        try {
            result = main.call(context, scope, null, new Object[] { doc.toString(), viewFun });
        } catch (final JavaScriptException e) {
            LOG.warn(doc + " caused exception during conversion.", e);
            return NO_DOCUMENTS;
        }

        if (result == null || result instanceof Undefined) {
            return NO_DOCUMENTS;
        }

        if (result instanceof RhinoDocument) {
            final RhinoDocument rhinoDocument = (RhinoDocument) result;
            final Document document = rhinoDocument.toDocument(doc.getString("_id"), defaults, database);
            return new Document[] { document };
        }

        if (result instanceof NativeArray) {
            final NativeArray nativeArray = (NativeArray) result;
            final Document[] arrayResult = new Document[(int) nativeArray.getLength()];
            for (int i = 0; i < (int) nativeArray.getLength(); i++) {
                if (nativeArray.get(i, null) instanceof RhinoDocument) {
                    final RhinoDocument rhinoDocument = (RhinoDocument) nativeArray.get(i, null);
                    final Document document = rhinoDocument.toDocument(doc.getString("_id"), defaults, database);
                    arrayResult[i] = document;
                }
            }
            return arrayResult;
        }

        return null;
    }

    private String loadResource(final String name) throws IOException {
        final InputStream in = DocumentConverter.class.getClassLoader().getResourceAsStream(name);
        try {
            return IOUtils.toString(in, "UTF-8");
        } finally {
            in.close();
        }
    }

}
