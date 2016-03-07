import generic.{Event, Versioned}

// Model

case class Article(id: String, version: Long, title: String, authorId: String, text: Option[String]) extends Versioned

case class Author(id: String, version: Long, firstName: String, lastName: String) extends Versioned

// events

sealed trait ArticleEvent extends Event

case class ArticleCreated(id: String, version: Long, title: String, authorId: String, text: Option[String]) extends ArticleEvent
case class ArticleTextChanged(id: String, version: Long, text: Option[String]) extends ArticleEvent
case class ArticleDeleted(id: String, version: Long) extends ArticleEvent

sealed trait AuthorEvent extends Event

case class AuthorCreated(id: String, version: Long, firstName: String, lastName: String) extends AuthorEvent
case class AuthorNameChanged(id: String, version: Long, firstName: String, lastName: String) extends AuthorEvent
case class AuthorDeleted(id: String, version: Long) extends AuthorEvent