package org.key_project.key.api.doc

import java.io.PrintWriter
import java.io.StringWriter
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * @author Alexander Weigl
 * @version 1 (29.10.23)
 */
abstract class PythonGenerator(protected val metamodel: Metamodel.KeyApi) : Supplier<String> {
    protected val target: StringWriter = StringWriter()
    protected val out: PrintWriter = PrintWriter(target)

    override fun get(): String {
        run()
        return target.toString()
    }

    protected abstract fun run()

    protected fun asPython(typeName: String): String? = when (typeName) {
            "INT", "LONG" -> "int"

            "STRING" -> "str"

            "BOOL" -> "bool"

            "DOUBLE" -> "float"

            else -> {
                val t = findType(typeName)
                asPython(t)
            }
        }

    fun asPython(t: Metamodel.Type): String? = when (t) {
            is Metamodel.ListType -> "typing.List[" + asPython(t.componentType) + "]"
            is Metamodel.EitherType -> "typing.Union[" + asPython(t.a) + ", " + asPython(t.b) + "]"
            Metamodel.INT, Metamodel.LONG -> "int"
            Metamodel.STRING -> "str"
            Metamodel.BOOL -> "bool"
            Metamodel.DOUBLE -> "float"
            else -> t.name
        }

    fun findType(typeName: String?): Metamodel.Type = this.metamodel.types.values
            .firstOrNull {
                if (it is Metamodel.ListType) {
                    it.componentType.name == typeName
                } else {
                    it.name == typeName
                }
            } ?: Metamodel.ObjectType("...", "...", listOf(), null)

    class PyApiGen(metamodel: Metamodel.KeyApi) : PythonGenerator(metamodel) {
        override fun run() {
            out.format(
                """
                    from __future__ import annotations
                    from .keydata import *
                    from .rpc import ServerBase, LspEndpoint

                    import enum
                    import abc
                    import typing
                    from abc import abstractmethod

                    """.trimIndent()
            )
            server(
                metamodel.endpoints.asSequence()
                    .filter { it is Metamodel.ServerRequest || it is Metamodel.ServerNotification }
                    .sortedBy { it.name }
            )

            client(
                metamodel.endpoints.asSequence()
                    .filter { it is Metamodel.ClientRequest || it is Metamodel.ClientNotification }
                    .sortedBy { it.name }
            )
        }

        private fun client(sorted: Sequence<Metamodel.Endpoint>) {
            out.format("class Client(abc.ABCMeta):%n")
            sorted.forEach { clientEndpoint(it) }
        }

        private fun clientEndpoint(endpoint: Metamodel.Endpoint) {
            val args =
                endpoint.args.joinToString(", ") { "${it.name}: ${asPython(it.type)}" }
            out.format("    @abstractmethod%n")
            if (endpoint is Metamodel.ClientRequest) {
                out.format(
                    "    def %s(self, %s) -> %s:%n", endpoint.name.replace("/", "_"), args,
                    asPython(endpoint.returnType)
                )
            } else {
                out.format("    def %s(self, %s):%n", endpoint.name.replace("/", "_"), args)
            }
            out.format("        \"\"\"%s\"\"\"%n%n", endpoint.documentation)
            out.format("        pass")
            out.println()
            out.println()
        }

        private fun server(sorted: Sequence<Metamodel.Endpoint>) {
            out.format(
                """
                    class KeyServer(ServerBase):%n
                        def __init__(self, endpoint : LspEndpoint):
                            super().__init__(endpoint)

                    """.trimIndent()
            )
            sorted.forEach { serverEndpoint(it) }
        }

        private fun serverEndpoint(endpoint: Metamodel.Endpoint) {
            val args = endpoint.args.joinToString(", ") { "${it.name}: ${asPython(it.type)}" }
            var params = "[]"
            if (!endpoint.args.isEmpty()) {
                params = endpoint.args.joinToString(" , ", "[", "]") { it.name }
            }

            if (endpoint is Metamodel.ServerRequest) {
                out.format(
                    "    def %s(self, %s) -> %s:%n", endpoint.name.replace("/", "_"), args,
                    asPython(endpoint.returnType)
                )
                out.format("       \"\"\"%s\"\"\"%n%n", endpoint.documentation)
                out.format(
                    "       return self._call_sync(\"%s\", %s)".format(endpoint.name, params)
                )
            } else {
                out.format("    def %s(self, %s):%n", endpoint.name.replace("/", "_"), args)
                out.format("        \"\"\"%s\"\"\"%n%n", endpoint.documentation)
                out.format(
                    "        return self._call_async(\"%s\", %s)".format(
                        endpoint.name,
                        params
                    )
                )
            }
            out.println()
            out.println()
        }
    }

    class PyDataGen(metamodel: Metamodel.KeyApi) : PythonGenerator(metamodel) {
        override fun run() {
            out.format(
                """
                    from __future__ import annotations
                    import enum
                    import abc
                    import typing
                    from abc import abstractmethod, ABCMeta



                    """.trimIndent()
            )
            metamodel.types.values.forEach(Consumer { type: Metamodel.Type? -> this.printType(type) })

            val names: String =
                metamodel.types.values.joinToString(", ") {
                    "\"%s\": %s".format(
                        it.identifier,
                        it.name
                    )
                }
            out.format("KEY_DATA_CLASSES = { %s }%n%n", names)

            val namesReverse: String =
                metamodel.types.values
                    .map {
                        "\"%s\": \"%s\"".format(it.name, it.identifier)
                    }
                    .joinToString(",")
            out.format("KEY_DATA_CLASSES_REV = { %s }%n%n", namesReverse)
        }

        private fun printType(type: Metamodel.Type?) {
            if (type is Metamodel.ObjectType) {
                out.format("class %s:%n".format(type.name))
                out.format("    \"\"\"%s\"\"\"%n", type.documentation)
                type.fields.forEach(
                    Consumer { it: Metamodel.Field? ->
                    out.format(
                        "%n    %s : %s%n    \"\"\"%s\"\"\"%n"
                            .format(it!!.name, asPython(it.type), it.documentation)
                    )
                }
                )

                out.format(
                    "\n    def __init__(self%s):%n".format(
                        type.fields.joinToString(", ", ", ", "") { it.name }
                    )
                )

                if (type.fields.isEmpty()) out.format("        pass%n%n")

                for (field in type.fields) {
                    out.format("        self.%s = %s%n", field.name, field.name)
                }
            } else if (type is Metamodel.EnumType) {
                out.format("class %s(enum.Enum):%n".format(type.name))
                out.format("    \"\"\"%s\"\"\"%n%n", type.documentation)
                type.values.forEach { out.format("    %s = None%n".format(it)) }
            }
            out.println()
        }
    }
}
