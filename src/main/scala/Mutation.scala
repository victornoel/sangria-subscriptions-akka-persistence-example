import java.util.UUID

import schema.MutationError
import generic.View.Get
import sangria.macros.derive.GraphQLField

import akka.pattern.ask

trait Mutation {
  this: Ctx ⇒

  @GraphQLField
  def createAuthor(firstName: String, lastName: String) =
    addEvent[Author](authors, AuthorCreated(UUID.randomUUID.toString, 1, firstName, lastName))

  @GraphQLField
  def changeAuthorName(id: String, version: Long, firstName: String, lastName: String) =
    loadLatestVersion(id, version) flatMap (version ⇒
      addEvent[Author](authors, AuthorNameChanged(id, version, firstName, lastName)))

  @GraphQLField
  def deleteAuthor(id: String, version: Long) =
    for {
      version ← loadLatestVersion(id, version)
      author ← (authors ? Get(id)).mapTo[Option[Author]]
      _ ← addDeleteEvent(AuthorDeleted(id, version))
    } yield author

  @GraphQLField
  def createArticle(title: String, authorId: String, text: Option[String]) =
    (authors ? Get(authorId)) flatMap {
      case Some(author: Author) ⇒
        addEvent[Article](articles, ArticleCreated(UUID.randomUUID.toString, 1, title, author.id, text))
      case _ ⇒
        throw MutationError(s"Author with ID '$authorId' does not exist.")
    }

  @GraphQLField
  def changeArticleText(id: String, version: Long, text: Option[String]) =
    loadLatestVersion(id, version) flatMap (version ⇒
      addEvent[Article](articles, ArticleTextChanged(id, version, text)))

  @GraphQLField
  def deleteArticle(id: String, version: Long) =
    for {
      version ← loadLatestVersion(id, version)
      author ← (articles ? Get(id)).mapTo[Option[Article]]
      _ ← addDeleteEvent(ArticleDeleted(id, version))
    } yield author
}
