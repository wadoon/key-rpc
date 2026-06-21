package org.key_project.key.api.doc

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier.DefaultKeyword.*
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.PrimitiveType.Primitive.*
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.VoidType
import java.util.function.Supplier

private fun NodeWithJavadoc<*>.addJavadoc(text: Metamodel.HelpText?) {
    if (text != null) {
        this.setJavadocComment(text.text)
    }
}

/**
 * @author Alexander Weigl
 * @version 1 (29.10.23)
 */
abstract class JavaGenerator(protected val metamodel: Metamodel.KeyApi) : Supplier<CompilationUnit> {
    val BASE_PACKAGE = "org.key_project.key.api.client"
    val PACKAGE = "$BASE_PACKAGE.stubs"

    fun CompilationUnit.className(name: String, packageName: String = BASE_PACKAGE): Type =
        ClassOrInterfaceType(null, name)

    protected fun adJava(typeName: String): Type {
        return when (typeName) {
            Metamodel.INT.name, "INT" -> PrimitiveType(INT)
            Metamodel.LONG.name, "LONG" -> PrimitiveType(LONG)
            Metamodel.STRING.name, "STRING" -> ClassOrInterfaceType(null, "String")
            Metamodel.BOOL.name, "BOOL" -> PrimitiveType(BOOLEAN)
            Metamodel.DOUBLE.name, "DOUBLE" -> PrimitiveType(DOUBLE)
            else -> {
                val t = findType(typeName)
                adJava(t)
            }
        }
    }

    fun listType(t: Type) = ClassOrInterfaceType(null, SimpleName("List"), NodeList(t))
    fun eitherType(a: Type, b: Type) = ClassOrInterfaceType(null, SimpleName("Either"), NodeList(a, b))

    fun adJava(t: Metamodel.Type): Type {
        return when (t) {
            is Metamodel.ListType -> listType(adJava(t.componentType))
            is Metamodel.EitherType -> eitherType(adJava(t.a), adJava(t.b))
            Metamodel.INT -> PrimitiveType(INT)
            Metamodel.LONG -> PrimitiveType(LONG)
            Metamodel.STRING -> ClassOrInterfaceType(null, "String")
            Metamodel.BOOL -> PrimitiveType(BOOLEAN)
            Metamodel.DOUBLE -> PrimitiveType(DOUBLE)
            is Metamodel.EnumType,
            is Metamodel.ObjectType -> ClassOrInterfaceType(null, t.name)
        }
    }

    fun findType(typeName: String?): Metamodel.Type {
        return this.metamodel.types.values
            .firstOrNull {
                if (it is Metamodel.ListType) it.componentType.name == typeName
                else it.name == typeName
            } ?: Metamodel.ObjectType("Object", "Object", listOf(), null)
    }

    class JavaApiGenServer(metamodel: Metamodel.KeyApi) : JavaGenerator(metamodel) {
        override fun get(): CompilationUnit {
            val sorted = metamodel.endpoints.asSequence()
                .filter { it is Metamodel.ServerRequest || it is Metamodel.ServerNotification }
                .sortedBy { it.name }
                .groupBy { it.segment() }
                .toSortedMap()

            return CompilationUnit().apply {
                setPackageDeclaration(PACKAGE)
                addClass("KeyRemote", PUBLIC).apply {
                    addExtendedType("BaseRemote")
                    addImport("org.key_project.key.api.client.*")
                    addImport("java.util.*")
                    addImport("java.lang.*")
                    addImport("org.key_project.key.api.client.stubs.ApiModel.*")
                    addImport("java.lang.reflect.Type")
                    addImport("com.google.gson.reflect.TypeToken")

                    addConstructor(PUBLIC).apply {
                        body()?.addStatement("super(rpcLayer);")
                        addParameter(className("RPCLayer"), "rpcLayer")
                    }

                    sorted.forEach { (name, sorted) ->
                        val cname = "Segment${name.capitalize()}"
                        this.addMember(ClassOrInterfaceDeclaration().apply {
                            setName(cname)
                            sorted.forEach { addMember(serverEndpoint(it)) }
                            addJavadoc(metamodel.segmentDocumentation[name])
                        })

                        addField(cname, name, PUBLIC, FINAL).variables().first()
                            .setInitializer("new Segment${name.capitalize()}()")
                    }
                }
            }
        }

        private fun serverEndpoint(endpoint: Metamodel.Endpoint): MethodDeclaration = MethodDeclaration().apply {
            addModifier(PUBLIC, FINAL)
            setName(endpoint.name.replace(".*/".toRegex(), ""))
            endpoint.args.forEach {
                addParameter(adJava(it.type), it.name)
            }
            addJavadoc(endpoint.documentation)
            val args = listOf(StringLiteralExpr(endpoint.name)) + endpoint.args.map { NameExpr(it.name) }
            val call = MethodCallExpr(null, "x", NodeList(args))
            if (endpoint is Metamodel.ServerRequest) {
                call.setName("_call_sync")
                val t = adJava(endpoint.returnType)
                setType(t.clone())

                if(endpoint.returnType is Metamodel.EitherType || endpoint.returnType is Metamodel.ListType) {
                    val s = "Type fooType = new TypeToken<$t>() {}.getType();"
                    body()?.addStatement(s)
                    call.arguments().add(0, NameExpr("fooType"))
                }else {
                    call.arguments().add(0, FieldAccessExpr(TypeExpr(t), "class"))
                }

                body()?.addStatement(ReturnStmt(call))
            } else {
                call.setName("_call_async")
                body()?.addStatement(ExpressionStmt(call))
                setType(VoidType())
            }
        }
    }

    class JavaApiGenClient(metamodel: Metamodel.KeyApi) : JavaGenerator(metamodel) {
        override fun get(): CompilationUnit {
            val sorted =
                metamodel.endpoints.asSequence()
                    .filter { it is Metamodel.ClientRequest || it is Metamodel.ClientNotification }
                    .sortedBy { it.name }

            return CompilationUnit().apply {
                setPackageDeclaration(PACKAGE)
                addImport("org.key_project.key.api.client.*")
                addImport("org.key_project.key.api.client.stubs.ApiModel.*")
                addClass("KeyClient", PUBLIC).apply {
                    isInterface = true
                    addExtendedType("BaseLocal")
                    sorted.forEach { addMember(clientEndpoint(it)) }
                }
            }
        }

        private fun clientEndpoint(endpoint: Metamodel.Endpoint) = MethodDeclaration().apply {
            setName(endpoint.name.replace("client/", ""))
            endpoint.args.forEach {
                addParameter(adJava(it.type), it.name)
            }

            if (endpoint is Metamodel.ClientRequest) {
                setType(adJava(endpoint.returnType))
                setBody(null)
            } else {
                addModifier(DEFAULT)
                setType(VoidType())
            }
            addJavadoc(endpoint.documentation)
        }
    }

    class JavaDataGen(metamodel: Metamodel.KeyApi) : JavaGenerator(metamodel) {
        override fun get(): CompilationUnit {
            return CompilationUnit().apply {
                setPackageDeclaration(PACKAGE)
                addImport("org.key_project.key.api.client.BaseLocal")
                addClass("ApiModel", PUBLIC, FINAL).apply {
                    metamodel.types.values.forEach {
                        addMember(printType(it))
                    }

                    val names: String =
                        metamodel.types.values.joinToString(", ") {
                            "\"%s\": %s".format(
                                it.identifier,
                                it.name
                            )
                        }

                    //out.format("KEY_DATA_CLASSES = { %s }%n%n", names)
                    /*val namesReverse: String =
                metamodel.types.values
                    .map {
                        "\"%s\": \"%s\"".format(it.name, it.identifier)
                    }
                    .joinToString(",")
            out.format("KEY_DATA_CLASSES_REV = { %s }%n%n", namesReverse)
             */
                }
            }
        }

        private fun printType(type: Metamodel.Type): TypeDeclaration<*> =
            if (type is Metamodel.ObjectType) {
                ClassOrInterfaceDeclaration().apply {
                    setName(type.name)
                    addJavadoc(type.documentation)
                    addAnnotation("lombok.Data")
                    addModifier(PUBLIC, STATIC, FINAL)
                    type.fields.forEach {
                        addField(adJava(it.type), it.name, PRIVATE)
                            .addJavadoc(it.documentation)
                    }
                }
            } else if (type is Metamodel.EnumType) {
                EnumDeclaration().apply {
                    setName(type.name)
                    addJavadoc(type.documentation)
                    type.values.forEach {
                        addEnumConstant(it.value).addJavadoc(it.documentation)
                    }
                }
            } else {
                error("!!!")
            }
    }
}
