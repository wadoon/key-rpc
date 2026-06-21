package org.key_project.key.api.doc

import java.util.function.Supplier

/**
 * Generation of Markdown documentation.
 * # Module sample
 * Sample examples for Dokka Mermaid plugin.
 *
 * ```mermaid
 * pie title NETFLIX
 *          "Time spent looking for movie" : 90
 *          "Time spent watching it" : 10
 * ```
 *
 * # Package com.glureau.dokkamermaid.sample
 *
 * Package documentation can have its own mermaid diagrams.
 *
 * ```mermaid
 * mindmap
 *   root((mindmap))
 *     Origins
 *       Long history
 *       ::icon(fa fa-book)
 *       Popularisation
 *         British popular psychology author Tony Buzan
 *     Research
 *       On effectiveness<br/>and features
 *       On Automatic creation
 *         Uses
 *             Creative techniques
 *             Strategic planning
 *             Argument mapping
 *     Tools
 *       Pen and paper
 *       Mermaid
 * ```
 *
 *
 * @author Alexander Weigl
 * @version 1 (29.10.23)
 */
class DocGen(private val metamodel: Metamodel.KeyApi) : Supplier<String> {
    override fun get() = HtmlDocs(metamodel).render()
}
