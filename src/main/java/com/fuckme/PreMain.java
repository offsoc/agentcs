package com.fuckme;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

public class PreMain {
    public static void premain(String agentArgs, Instrumentation inst) {
        if (agentArgs == null) {
            System.out.println("[CSAgent] Agent options not found!");
            return;
        }
        inst.addTransformer(new CobaltStrikeTransformer(agentArgs), true);
    }

    static class CobaltStrikeTransformer implements ClassFileTransformer {
        private final ClassPool classPool = ClassPool.getDefault();
        private final String hexkey;
        private final Boolean needTranslation;

        public CobaltStrikeTransformer(String args) {
            this.hexkey = args;
            this.needTranslation = Files.exists(Paths.get("resources/translation.txt"));
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            // System.out.println("premain load Class:" + className);
            try {
                if (className == null) {
                    return classfileBuffer;
                
                } else if (className.equals("common/Authorization")) {
                    // 设置破解key
                    CtClass cls = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    String func = "public static byte[] hex2bytes(String s) {" +
                            "   int len = s.length();" +
                            "   byte[] data = new byte[len / 2];" +
                            "   for (int i = 0; i < len; i += 2) {" +
                            "       data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));" +
                            "   }" +
                            "   return data;" +
                            "}";
                    CtMethod hex2bytes = CtNewMethod.make(func, cls);
                    cls.addMethod(hex2bytes);

                    CtConstructor mtd = cls.getDeclaredConstructor(new CtClass[]{});
                    mtd.setBody("{$0.watermark = 1234567890;" +
                            "$0.validto = \"forever\";" +
                            "$0.valid = true;" +
                            "common.MudgeSanity.systemDetail(\"valid to\", \"perpetual\");" +
                            "common.MudgeSanity.systemDetail(\"id\", String.valueOf($0.watermark));" +
                            "common.SleevedResource.Setup(hex2bytes(\"" + hexkey + "\"));" +
                            "}");
                    return cls.toBytecode();
                } else if (className.equals("common/MudgeSanity")) {
                    CtClass cls = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    CtMethod mtd = cls.getDeclaredMethod("systemInformation");
                    mtd.instrument(
                            new ExprEditor() {
                                public void edit(MethodCall m)
                                        throws CannotCompileException {
                                    if (m.getClassName().equals("java.lang.StringBuffer")
                                            && m.getMethodName().equals("append")) {
                                        m.replace("{" +
                                                "if ($1.startsWith(\"Version:\")) {" +
                                                "   $1 += \"Loader: https://github.com/Twi1ight/CSAgent\\n\";" +
                                                "}" +
                                                "$_ = $proceed($$);" +
                                                "}");
                                    }
                                }
                            });
                    return cls.toBytecode();
                } else if (className.equals("beacon/BeaconCommands")) {
                    if (Files.notExists(Paths.get("resources/bhelp.txt"))) {
                        return classfileBuffer;
                    }
                    System.out.println("[CSAgent] load translation resource");
                    CtClass cls = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    String[] funcs = {"loadCommands", "loadDetails"};
                    for (String func : funcs) {
                        CtMethod cm = cls.getDeclaredMethod(func);
                        cm.instrument(
                                new ExprEditor() {
                                    public void edit(MethodCall m)
                                            throws CannotCompileException {
                                        if (m.getClassName().equals("common.CommonUtils")
                                                && m.getMethodName().equals("bString")) {
                                            m.replace("{ $_ = new String($1, \"UTF-8\"); }");
                                        }
                                    }
                                });
                    }
                    return cls.toBytecode();
                } else if (className.equals("sleep/runtime/ScriptLoader")) {
                    // 解决Windows上中文乱码问题
                    if (Files.notExists(Paths.get("scripts/default.cna"))) {
                        return classfileBuffer;
                    }
                    CtClass cls = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    CtMethod mtd = cls.getDeclaredMethod("getInputStreamReader");
                    mtd.insertBefore("setCharset(\"UTF-8\");");
                    return cls.toBytecode();
                }
                if (needTranslation) {
                    // JButton的父类是AbstractButton，需要setActionCommand，否则翻译后无法正确执行Button的操作
                    if (className.equals("javax/swing/AbstractButton")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtMethod ctMethod = ctClass.getDeclaredMethod("setText");
                        insertTranslateCommand(ctMethod, 1, false);
                        ctMethod.insertBefore("{setActionCommand($1);}");
                        // ctMethod.insertBefore("System.out.printf(\"AbstractButton.setText: %s\\n\", new Object[]{$1});");
                        return ctClass.toBytecode();
                    }
                    if (className.equals("javax/swing/JLabel")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtMethod ctMethod = ctClass.getDeclaredMethod("setText");
                        insertTranslateCommand(ctMethod, 1, false);
                        // ctMethod.insertBefore("System.out.printf(\"JLabel.setText: %s\\n\", new Object[]{$1});");
                        return ctClass.toBytecode();
                    }
                    if (className.equals("javax/swing/JComponent")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtMethod ctMethod = ctClass.getDeclaredMethod("setToolTipText");
                        insertTranslateCommand(ctMethod, 1, false);
                        // ctMethod.insertBefore("System.out.printf(\"JComponent.setToolTipText: %s\\n\", new Object[]{$1});");
                        return ctClass.toBytecode();
                    }
                    // JOptionPane.showMessageDialog 最终都会调用showOptionDialog
                    // 大部分消息都是带参数的，所以需要使用正则匹配
                    if (className.equals("javax/swing/JOptionPane")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        //showOptionDialog 参数3是Title可被JDialog覆盖，参数2是Message，常用于提示信息，需正则替换
                        CtMethod ctMethod = ctClass.getDeclaredMethod("showOptionDialog",
                                new CtClass[]{
                                        classPool.get("java.awt.Component"),
                                        classPool.get("java.lang.Object"),
                                        classPool.get("java.lang.String"),
                                        CtClass.intType,
                                        CtClass.intType,
                                        classPool.get("javax.swing.Icon"),
                                        classPool.get("java.lang.Object[]"),
                                        classPool.get("java.lang.Object"),
                                });
                        insertTranslateCommand(ctMethod, 2, true);
                        return ctClass.toBytecode();
                    }
                    // 各类文件打开窗口标题
                    if (className.equals("javax/swing/JDialog")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtConstructor ctConstructor = ctClass.getDeclaredConstructor(new CtClass[]{classPool
                                .get("java.awt.Frame"), classPool.get("java.lang.String"), CtClass.booleanType});
                        insertTranslateCommand(ctConstructor, 2, false);
                        // ctConstructor.insertBefore("System.out.printf(\"JDialog.setText: %s\\n\", new Object[]{$2});");
                        return ctClass.toBytecode();
                    }
                    // About等窗口标题
                    if (className.equals("javax/swing/JFrame")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtConstructor ctConstructor = ctClass.getDeclaredConstructor(new CtClass[]{classPool.get("java.lang.String")});
                        insertTranslateCommand(ctConstructor, 1, false);
                        // ctConstructor.insertBefore("System.out.printf(\"JFrame: %s\\n\", new Object[]{$1});");
                        return ctClass.toBytecode();
                    }
                    // 各个Dialog上面的描述
                    if (className.equals("javax/swing/JEditorPane")) {
                        CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
                        CtMethod ctMethod = ctClass.getDeclaredMethod("setText");
                        insertTranslateCommand(ctMethod, 1, false);
                        // ctMethod.insertBefore("System.out.printf(\"JEditorPane.setText: %s\\n\", new Object[]{$1});");
                        return ctClass.toBytecode();
                    }
                }
            } catch (Exception ex) {
                System.out.printf("[CSAgent] PreMain transform fucked up: %s\n", ex);
            }
            return classfileBuffer;
        }

        CtBehavior insertTranslateCommand(CtBehavior ctMethod, int n, Boolean regex) throws CannotCompileException {
            StringBuilder stringBuffer = new StringBuilder();
            stringBuffer.append("{");
            stringBuffer.append("ClassLoader classLoader = ClassLoader.getSystemClassLoader();");
            stringBuffer.append("Class translator = classLoader.loadClass(\"com.fuckme.Translator\");");
            if (regex) {
                stringBuffer.append("java.lang.reflect.Method method = translator.getDeclaredMethod(\"regexTranslate\",new Class[]{String.class});");
            } else {
                stringBuffer.append("java.lang.reflect.Method method = translator.getDeclaredMethod(\"translate\",new Class[]{String.class});");
            }
            stringBuffer.append(String.format("if($%d instanceof String){$%d = (String)method.invoke(null, new Object[]{$%d});}", n, n, n));
            stringBuffer.append("}");
            StringBuilder outer = new StringBuilder();
            outer.append("if ((javax.swing.table.DefaultTableCellRenderer.class.isAssignableFrom($0.getClass())  && !sun.swing.table.DefaultTableCellHeaderRenderer.class.isAssignableFrom($0.getClass()))  || javax.swing.text.DefaultStyledDocument.class.isAssignableFrom($0.getClass())  || javax.swing.tree.DefaultTreeCellRenderer.class.isAssignableFrom($0.getClass())  || $0.getClass().getName().equals(\"javax.swing.plaf.synth.SynthComboBoxUI$SynthComboBoxRenderer\")) {} else");
            outer.append(stringBuffer);
            try {
                ctMethod.insertBefore(outer.toString());
            } catch (Exception e) {
                ctMethod.insertBefore(stringBuffer.toString());
            }
            return ctMethod;
        }
    }
}
