package org.key_project.key.api.doc

import kotlinx.serialization.Serializable
import org.keyproject.key.api.data.DataExamples
import java.util.*

/** Metamodel of the API. This class contains classes which represents the functionality and
 * interfaces of the API.
 *
 * @author Alexander Weigl
 * @version 1 (29.10.23)
 */
class Metamodel {
    /** Root class of the metamodel.
     *
     * @param endpoints a list of provided services
     * @param types a list of known types
     */
    @Serializable
    data class KeyApi(
        val endpoints: MutableList<Endpoint>,
        val types: MutableMap<String, Type>,
        val segmentDocumentation: MutableMap<String, HelpText>
    )

    /** Javadoc texts */
    @Serializable
    data class HelpText(val text: String, val others: MutableList<HelpTextEntry>)

    /** Javadoc categories */
    @Serializable
    data class HelpTextEntry(val name: String, val value: String)

    /** An [Endpoint] is a provided service/method. */
    @Serializable
    sealed interface Endpoint {
        /** complete name of the service */
        val name: String

        /** a markdown documentation */
        val documentation: HelpText?

        fun kind(): String = javaClass.getName()

        /** a list of its arguments */
        val args: List<Argument>

        /** */
        fun segment(): String {
            val idx = name.indexOf("/")
            if (idx == -1) {
                return ""
            }
            return name.substring(0, idx)
        }

        /** sender of this invocation */
        val sender
            get() = if (javaClass.getSimpleName().startsWith("Server")) "Client" else "Server"

        val isAsync: Boolean
            get() = javaClass.getSimpleName().endsWith("Notification")
    }

    /** A [Argument] of an endpoint
     *
     * @param name the argument name
     * @param type the argument type
     */
    @Serializable
    data class Argument(val name: String, val type: String)

    @Serializable
    sealed interface Request : Endpoint {
        val returnType: Type
    }

    /** A [ServerRequest] is the caller to the callee expecting an answer.
     *
     * @param name
     * @param args
     * @param documentation
     * @param returnType
     */
    @Serializable
    data class ServerRequest(
        override val name: String,
        override val documentation: HelpText?,
        override val args: List<Argument>,
        override val returnType: Type
    ) : Request

    /** */
    @Serializable
    data class ServerNotification(
        override val name: String,
        override val documentation: HelpText?,
        override val args: List<Argument>
    ) : Endpoint

    /** */
    @Serializable
    data class ClientRequest(
        override val name: String,
        override val documentation: HelpText?,
        override val args: List<Argument>,
        override val returnType: Type
    ) : Request

    /** */
    @Serializable
    data class ClientNotification(
        override val name: String,
        override val documentation: HelpText?,
        override val args: List<Argument>
    ) : Endpoint

    /** */
    @Serializable
    data class Field(
        val name: String,  /* Type */
        val type: String,
        val documentation: HelpText?
    )

    /** A data type */
    @Serializable
    sealed interface Type {
        val kind
            get() = javaClass.getName()

        /** Documentation of the data type */
        val documentation: HelpText?

        /** name of the data type */
        val name: String

        /** */
        val identifier: String?
    }

    /** Typical built-in data types supported by the API */
    @Serializable
    sealed class BuiltinType(override val name: String) : Type {
        override val documentation: HelpText
            get() = HelpText("built-in data type", mutableListOf<HelpTextEntry>())

        override val identifier: String
            get() = name.lowercase(Locale.getDefault())
    }

    @Serializable
    object INT : BuiltinType("int")

    @Serializable
    object LONG : BuiltinType("long")

    @Serializable
    object STRING : BuiltinType("string")

    @Serializable
    object BOOL : BuiltinType("bool")

    @Serializable
    object DOUBLE : BuiltinType("double")

    /** List of `type`.
     *
     * @param componentType the type of list elements
     */
    @Serializable
    data class ListType(val componentType: Type) : Type {
        override val name
            get() = this.componentType.name + "[]"
        override val identifier
            get() = this.componentType.identifier + "[]"
        override val documentation = null
    }

    /** Data type of objects or struct or record.
     *
     * @param typeName short type name
     * @param typeFullName fully-qualified type name
     * @param fields list of fields
     * @param documentation documentation of data type
     */
    @Serializable
    data class ObjectType(
        val typeName: String,
        val typeFullName: String,
        val fields: List<Field>,
        override val documentation: HelpText?
    ) : Type {
        override val name = typeName
        override val identifier = typeFullName

        /** */
        fun jsonExample(): String? = DataExamples.get(typeFullName)
    }

    /** A data type representing that a method returns or expecting either type `a` or `b`.
     *
     * @param a
     * @param b
     */
    @Serializable
    data class EitherType(@JvmField val a: Type, @JvmField val b: Type) : Type {
        override val name = "either<a,b>"
        override val identifier = name
        override val documentation = null
    }

    /** Enumeration data type
     *
     * @param typeName short name of the data type
     * @param typeFullName fully-qualified name
     * @param values possible values of the enum
     * @param documentation documentation of the data type
     */
    @Serializable
    data class EnumType(
        val typeName: String,
        val typeFullName: String,
        val values: MutableList<EnumConstant>,
        override val documentation: HelpText
    ) : Type {
        override val name = typeName
        override val identifier = typeFullName
    }

    @Serializable
    data class EnumConstant(val value: String, val documentation: HelpText?)
}
