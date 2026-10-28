package com.hmdm.launcher.impressbox.localapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.launcher.BuildConfig;
import com.impressbox.launcher.localapi.ILocalApi;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boundary player <-> launcher: the launcher's local API must match docs/launcher-local-api.json, the contract that
 * impress-player checks too (app/android/helpers/launcherApi). Change both repositories together.
 */
public class LocalApiContractTest {

    private static JsonNode contract;
    private static String aidl;

    @BeforeClass
    public static void load() throws IOException {
        File root = repositoryRoot();
        contract = new ObjectMapper().readTree(new File(root, "docs/launcher-local-api.json"));
        aidl = new String(Files.readAllBytes(new File(root,
                "app/src/main/aidl/com/impressbox/launcher/localapi/ILocalApi.aidl").toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void versionAndService() {
        assertEquals(LocalApi.API_VERSION, contract.get("apiVersion").asInt());
        JsonNode service = contract.get("service");
        assertEquals(BuildConfig.APPLICATION_ID, service.get("package").asText());
        assertEquals(LocalApi.ACTION, service.get("action").asText());
        assertEquals(ILocalApi.class.getName(), service.get("interface").asText());
    }

    @Test
    public void aidlMatchesTheContract() {
        Matcher pkg = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(withoutComments(aidl));
        assertTrue("package in the AIDL", pkg.find());
        Matcher name = Pattern.compile("interface\\s+(\\w+)").matcher(withoutComments(aidl));
        assertTrue("interface in the AIDL", name.find());
        assertEquals(contract.get("service").get("interface").asText(), pkg.group(1) + "." + name.group(1));
        assertEquals(texts(contract.get("service").get("methods")), aidlMethods(aidl));
    }

    @Test
    public void resultCodes() throws IllegalAccessException {
        JsonNode codes = contract.get("codes");
        Set<String> inContract = new HashSet<>();
        for (Iterator<String> it = codes.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            inContract.add(name);
            assertEquals(name, codes.get(name).asInt(), intConstant(name));
        }
        // Every int constant of LocalApi but the version is a result code of the contract
        for (Field field : LocalApi.class.getFields()) {
            if (field.getType() == int.class && Modifier.isStatic(field.getModifiers()) && !field.getName().equals("API_VERSION")) {
                assertTrue(field.getName() + " is not in the contract", inContract.contains(field.getName()));
            }
        }
        assertEquals("code", LocalApi.KEY_CODE);
        assertEquals("message", LocalApi.KEY_MESSAGE);
        assertTrue(contract.get("result").has(LocalApi.KEY_CODE));
        assertTrue(contract.get("result").has(LocalApi.KEY_MESSAGE));
    }

    @Test
    public void commands() throws IllegalAccessException {
        JsonNode commands = contract.get("commands");
        List<String> names = new ArrayList<>();
        commands.fieldNames().forEachRemaining(names::add);
        assertEquals(LocalApiRules.COMMANDS, names);

        Set<String> args = stringConstants("ARG_");
        Set<String> results = stringConstants("RESULT_");
        for (String command : names) {
            JsonNode spec = commands.get(command);
            assertEquals(command + " minSdk", spec.get("minSdk").asInt(), LocalApiRules.minSdk(command));
            assertEquals(command + " deviceOwner", spec.get("deviceOwner").asBoolean(), LocalApiRules.needsDeviceOwner(command));
            spec.get("args").fieldNames().forEachRemaining(arg ->
                    assertTrue(command + " argument " + arg + " has no LocalApi.ARG_ constant", args.contains(arg)));
            spec.get("results").fieldNames().forEachRemaining(key ->
                    assertTrue(command + " result " + key + " has no LocalApi.RESULT_ constant", results.contains(key)));
        }
    }

    // The method declarations of the AIDL interface, comments removed and spaces normalized
    static List<String> aidlMethods(String text) {
        Matcher body = Pattern.compile("(?s)interface\\s+\\w+\\s*\\{(.*)\\}").matcher(withoutComments(text));
        if (!body.find()) {
            fail("no interface body in the AIDL");
        }
        List<String> methods = new ArrayList<>();
        for (String statement : body.group(1).split(";")) {
            if (!statement.trim().isEmpty()) {
                methods.add(signature(statement));
            }
        }
        return methods;
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(signature(node.asText()));
        }
        return result;
    }

    // "List < String >  getCapabilities ( )" -> "List<String> getCapabilities()"
    static String signature(String text) {
        return text.trim().replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),<>])\\s*", "$1")
                .replace(",", ", ")
                .replaceAll(">(\\w)", "> $1");
    }

    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static int intConstant(String name) throws IllegalAccessException {
        try {
            return LocalApi.class.getField(name).getInt(null);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("LocalApi has no constant " + name);
        }
    }

    private static Set<String> stringConstants(String prefix) throws IllegalAccessException {
        Set<String> values = new HashSet<>();
        for (Field field : LocalApi.class.getFields()) {
            if (field.getName().startsWith(prefix) && field.getType() == String.class) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }

    // Unit tests run in the module directory; the contract lives at the repository root
    private static File repositoryRoot() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "docs/launcher-local-api.json").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("docs/launcher-local-api.json not found above " + new File("").getAbsolutePath());
    }
}
