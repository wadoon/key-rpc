package org.key_project.key.api.doc

import com.github.therapi.runtimejavadoc.*
import de.uka.ilkd.key.proof.Proof
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.keyproject.key.api.remoteapi.KeyApi
import org.keyproject.key.api.remoteclient.ClientApi
import java.io.File
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.stream.Stream

/**
 * @author Alexander Weigl
 * @version 1 (14.10.23)
 */
class ExtractMetaData : Runnable {
    private val endpoints: MutableList<Metamodel.Endpoint> = mutableListOf()
    private val types: MutableMap<String, Metamodel.Type> = mutableMapOf()
    private val segDocumentation: MutableMap<String, Metamodel.HelpText> = TreeMap()

    val api: Metamodel.KeyApi = Metamodel.KeyApi(endpoints, types, segDocumentation)

    override fun run() {
        for (method in KeyApi::class.java.getMethods()) {
            addServerEndpoint(method)
        }

        for (method in ClientApi::class.java.getMethods()) {
            addClientEndpoint(method)
        }

        for (anInterface in KeyApi::class.java.interfaces) {
            val js = anInterface.getAnnotation(JsonSegment::class.java)
            if (js != null) {
                val key = js.value
                val doc = findDocumentation(anInterface)
                doc?.let { segDocumentation.put(key, it) }
            }
        }
    }

    private fun addServerEndpoint(method: Method) {
        val jsonSegment = method.declaringClass.getAnnotation(JsonSegment::class.java) ?: return
        val segment: String = jsonSegment.value

        val req = method.getAnnotation(JsonRequest::class.java)
        val resp = method.getAnnotation(JsonNotification::class.java)

        val args = translate(method.parameters)

        if (req != null) {
            val mn: String = callMethodName(method.name, segment, req.value, req.useSegment)

            if (method.returnType == Void::class.java) {
                System.err.println("Found void as return type for a request!  $method")
                return
            }

            val retType = getOrFindType(method.genericReturnType)
            Objects.requireNonNull(retType, "No retType found " + method.genericReturnType)
            val documentation = findDocumentation(method)
            val mm = Metamodel.ServerRequest(mn, documentation, args, retType)
            endpoints.add(mm)
            return
        }

        if (resp != null) {
            val mn: String = callMethodName(method.name, segment, resp.value, resp.useSegment)
            val documentation = findDocumentation(method)
            val mm = Metamodel.ServerNotification(mn, documentation, args)
            endpoints.add(mm)
            return
        }

        throw IllegalStateException(
            "Method $method is neither a request nor a notification"
        )
    }

    private fun findDocumentation(method: Method): Metamodel.HelpText? {
        val javadoc = RuntimeJavadoc.getJavadoc(method)
        if (javadoc.isEmpty) return null

        val visitor = ToHtmlStringCommentVisitor()
        javadoc.comment.visit(visitor)

        val t = javadoc.throws.stream()
            .map { it: ThrowsJavadoc? ->
                Metamodel.HelpTextEntry(
                    it!!.name,
                    it.comment.toString()
                )
            }

        val p = javadoc.params.stream()
            .map { it: ParamJavadoc? ->
                Metamodel.HelpTextEntry(
                    it!!.name,
                    it.comment.toString()
                )
            }

        val r = Stream.of(Metamodel.HelpTextEntry("returns", javadoc.returns.toString()))

        return Metamodel.HelpText(
            visitor.build(),
            Stream.concat(r, Stream.concat(p, t)).toList()
        )
    }

    private fun translate(parameters: Array<Parameter>) =
        parameters.map { this.translate(it) }.toList()

    private fun translate(parameter: Parameter): Metamodel.Argument {
        val type = getOrFindType(parameter.getType())!!.name
        return Metamodel.Argument(parameter.name, type)
    }

    private fun getOrFindType(type: Class<*>): Metamodel.Type? {
        if (type == String::class.java) return Metamodel.STRING
        if (type == Int::class.java) return Metamodel.INT
        if (type == java.lang.Double::class.java) return Metamodel.DOUBLE
        if (type == java.lang.Long::class.java) return Metamodel.LONG
        if (type == Char::class.java) return Metamodel.LONG
        if (type == File::class.java) return Metamodel.STRING
        if (type == java.lang.Boolean::class.java) return Metamodel.BOOL
        if (type == java.lang.Boolean.TYPE) return Metamodel.BOOL

        if (type == Integer.TYPE) return Metamodel.INT
        if (type == java.lang.Double.TYPE) return Metamodel.DOUBLE
        if (type == java.lang.Long.TYPE) return Metamodel.LONG
        if (type == Character.TYPE) return Metamodel.LONG

        if (type == CompletableFuture::class.java) {
            return getOrFindType(type.getTypeParameters()[0].javaClass)
        }

        if (type == MutableList::class.java) {
            // TODO try to get the type below.
            val subType = getOrFindType(type.getTypeParameters()[0])
            return Metamodel.ListType(subType)
        }

        check(!(type == Class::class.java || type == Constructor::class.java || type == Proof::class.java)) { "Forbidden class reached!" }

        val t = types.get(type.name)
        if (t != null) return t
        val a = createType(type)
        types.put(type.name, a)
        return a
    }

    private fun createType(type: Class<*>): Metamodel.Type {
        val documentation = findDocumentation(type)
        if (type.isEnum) {
            return Metamodel.EnumType(
                type.getSimpleName(), type.getName(),
                Arrays.stream(type.getEnumConstants())
                    .map { it: Any? ->
                        Metamodel.EnumConstant(
                            it.toString(),
                            findDocumentationEnum(type, it!!)
                        )
                    }
                    .toList(),
                documentation!!
            )
        }

        val list = type.getDeclaredFields()
            .map {
                Metamodel.Field(
                    it.name, getOrFindTypeName(it.genericType),
                    if (type.isRecord) {
                        findDocumentationRecord(type, it.name)
                    } else {
                        findDocumentation(it)
                    }
                )
            }

        return Metamodel.ObjectType(
            type.getSimpleName(), type.getName(), list, documentation
        )
    }

    private fun findDocumentation(it: Field): Metamodel.HelpText? {
        val javadoc = RuntimeJavadoc.getJavadoc(it)
        if (javadoc.isEmpty) return null
        return printFieldDocumentation(javadoc)
    }

    private fun findDocumentationEnum(type: Class<*>, enumConstant: Any): Metamodel.HelpText? {
        val javadoc = RuntimeJavadoc.getJavadoc(type)
        if (javadoc.isEmpty) return null
        for (cdoc in javadoc.enumConstants) {
            if (cdoc.name.equals(enumConstant.toString(), ignoreCase = true)) {
                return printFieldDocumentation(cdoc)
            }
        }
        return null
    }

    private fun findDocumentationRecord(type: Class<*>, name: String?): Metamodel.HelpText? {
        val javadoc = RuntimeJavadoc.getJavadoc(type)
        if (javadoc.isEmpty) return null
        for (cdoc in javadoc.recordComponents) {
            if (cdoc.name.equals(name, ignoreCase = true)) {
                return Metamodel.HelpText(cdoc.comment.toString(), mutableListOf())
            }
        }
        return null
    }

    private fun findDocumentation(type: Class<*>): Metamodel.HelpText? {
        val classDoc = RuntimeJavadoc.getJavadoc(type)
        if (!classDoc.isEmpty) { // optionally skip absent documentation
            val other = classDoc.other
                .stream().map { it: OtherJavadoc? ->
                    Metamodel.HelpTextEntry(
                        it!!.name,
                        it.comment.toString()
                    )
                }
            val also = classDoc.seeAlso
                .stream().map { it: SeeAlsoJavadoc? ->
                    Metamodel.HelpTextEntry(
                        it!!.seeAlsoType.toString(),
                        it.stringLiteral
                    )
                }

            return Metamodel.HelpText(
                classDoc.comment.toString(),
                Stream.concat(other, also).toList()
            )
        }
        return null
    }

    private fun addClientEndpoint(method: Method) {
        val jsonSegment = method.declaringClass.getAnnotation(JsonSegment::class.java)
        val segment: String? = if (jsonSegment == null) "" else jsonSegment.value

        val req = method.getAnnotation(JsonRequest::class.java)
        val resp = method.getAnnotation(JsonNotification::class.java)

        val args = translate(method.parameters)

        if (req != null) {
            val retType = getOrFindType(method.genericReturnType)
            Objects.requireNonNull(retType)
            val mn: String = callMethodName(method.name, segment, req.value, req.useSegment)
            val documentation = findDocumentation(method)
            val mm = Metamodel.ClientRequest(mn, documentation, args, retType)
            endpoints.add(mm)
            return
        }

        if (resp != null) {
            val mn: String = callMethodName(method.name, segment, resp.value, resp.useSegment)
            val documentation = findDocumentation(method)
            val mm = Metamodel.ClientNotification(mn, documentation, args)
            endpoints.add(mm)
        }
    }

    private fun getOrFindTypeName(type: Type): String {
        when (type) {
            is GenericArrayType -> throw RuntimeException("Unwanted type found: $type")

            is Class<*> -> return getOrFindTypeName(type)

            is ParameterizedType -> {
                return when (val typeName = type.rawType.typeName) {
                    CompletableFuture::class.java.name -> {
                        getOrFindTypeName(type.actualTypeArguments[0])
                    }

                    List::class.java.name -> {
                        val base = getOrFindTypeName(type.actualTypeArguments[0])
                        "$base[]"
                    }

                    Either::class.java.name -> {
                        val base1 = getOrFindTypeName(type.actualTypeArguments[0])
                        val base2 = getOrFindTypeName(type.actualTypeArguments[1])
                        "either<$base1, $base2>"
                    }

                    else -> "unsupported parameterized type: $typeName"
                }
            }
        }
        return "<error>!!!"
    }

    private fun getOrFindTypeName(type: Class<*>): String {
        val t = types[type.name]
        if (t != null) return t.name

        return when (type) {
            String::class.java -> Metamodel.STRING.name

            Int::class.java -> Metamodel.INT.name

            Double::class.java -> Metamodel.DOUBLE.name

            Long::class.java -> Metamodel.LONG.name

            Char::class.java -> Metamodel.LONG.name

            File::class.java -> Metamodel.STRING.name

            Boolean::class.java -> Metamodel.BOOL.name

            java.lang.Boolean.TYPE -> Metamodel.BOOL.name

            Integer.TYPE -> Metamodel.INT.name

            java.lang.Double.TYPE -> Metamodel.DOUBLE.name

            java.lang.Long.TYPE -> Metamodel.LONG.name

            Character.TYPE -> Metamodel.LONG.name

            CompletableFuture::class.java -> {
                getOrFindTypeName(type.getTypeParameters()[0].javaClass)
            }

            MutableList::class.java -> {
                val subType = getOrFindTypeName(type.getTypeParameters()[0])
                "$subType[]"
            }

            else -> {
                check(!(type == Class::class.java || type == Constructor::class.java || type == Proof::class.java)) {
                    "Forbidden class reached!"
                }
                type.getSimpleName()
            }
        }
    }

    fun getOrFindType(type: Type): Metamodel.Type = when (type) {
            is Class<*> -> getOrFindType(type)!!

            is ParameterizedType -> {
                when (val typeName = type.rawType.typeName) {
                    CompletableFuture::class.java.name -> getOrFindType(type.actualTypeArguments[0])

                    List::class.java.name -> Metamodel.ListType(getOrFindType(type.actualTypeArguments[0]))

                    Either::class.java.name ->
                        Metamodel.EitherType(
                            getOrFindType(type.actualTypeArguments[0]),
                            getOrFindType(type.actualTypeArguments[1])
                        )

                    else -> error("unsupported parameterized type: $typeName")
                }
            }

            else -> error("Could not determine type for $type")
        }

    companion object {
        private fun printFieldDocumentation(javadoc: FieldJavadoc): Metamodel.HelpText {
            val visitor = ToHtmlStringCommentVisitor()
            javadoc.comment.visit(visitor)

            val t = javadoc.other.stream()
                .map(
                    Function { it: OtherJavadoc? ->
                    Metamodel.HelpTextEntry(
                        it!!.name,
                        it.comment.toString()
                    )
                }
                )

            val p = javadoc.seeAlso.stream().map(
                Function { it: SeeAlsoJavadoc? ->
                    Metamodel.HelpTextEntry(
                        it!!.seeAlsoType.toString(),
                        it.stringLiteral
                    )
                }
            )

            return Metamodel.HelpText(visitor.build(), Stream.concat(p, t).toList())
        }

        private fun callMethodName(
            method: String, segment: String?,
            userValue: String?,
            useSegment: Boolean
        ): String {
            if (!useSegment) {
                if (userValue == null || userValue.isBlank()) {
                    return method
                } else {
                    return userValue
                }
            } else {
                if (userValue == null || userValue.isBlank()) {
                    return "$segment/$method"
                } else {
                    return "$segment/$userValue"
                }
            }
        }
    }
}
