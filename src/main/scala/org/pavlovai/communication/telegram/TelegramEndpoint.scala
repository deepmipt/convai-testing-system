package org.pavlovai.communication.telegram

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import info.mukel.telegrambot4s.api._
import info.mukel.telegrambot4s.methods.{ParseMode, SendMessage}
import info.mukel.telegrambot4s.models._
import org.pavlovai.dialog.{Dialog, DialogFather}
import org.pavlovai.communication.{Endpoint, TelegramChat}

import scala.collection.mutable

/**
  * @author vadim
  * @since 04.07.17
  */
class TelegramEndpoint(daddy: ActorRef) extends Actor with ActorLogging with Stash {
  import TelegramEndpoint._

  override def receive: Receive = unititialized

  private val unititialized: Receive = {
    case SetGateway(gate) =>
      context.become(initialized(gate))
      unstashAll()

    case m => stash()
  }

  private def initialized(request: RequestHandler): Receive = {
    case SetGateway(g) => context.become(initialized(g))

    case Command(chat, "/start") =>
      request(SendMessage(Left(chat.id),
        """
          |*Welcome!*
          |
          |Use:
          |
          |- /begin for start talk
          |- /end for end talk
          |- /help for help
          |
          |[](http://vkurselife.com/wp-content/uploads/2016/05/b5789b.jpg)
        """.stripMargin, Some(ParseMode.Markdown)))

    case Command(chat, "/help") =>
      request(helpMessage(chat.id))

    case Command(Chat(id, ChatType.Private, _, username, _, _, _, _, _, _), "/begin") if isNotInDialog(id) =>
      daddy ! DialogFather.UserAvailable(TelegramChat(id))

    case Command(Chat(id, ChatType.Private, _, username, _, _, _, _, _, _), "/begin") if isInDialog(id) =>
      request(SendMessage(Left(id), "Messages of this type aren't supported \uD83D\uDE1E"))

    case Command(Chat(id, ChatType.Private, _, username, _, _, _, _, _, _), "/end") if isInDialog(id) =>
      daddy ! DialogFather.UserUnavailable(TelegramChat(id))

    case Command(Chat(id, ChatType.Private, _, username, _, _, _, _, _, _), "/end") if isNotInDialog(id) =>
      request(SendMessage(Left(id), "Messages of this type aren't supported \uD83D\uDE1E"))

    case Command(chat, _) =>
      request(SendMessage(Left(chat.id), "Messages of this type aren't supported \uD83D\uDE1E"))

    case Update(num, Some(message), _, _, _, _, _, _, _, _) if isInDialog(message.chat.id) =>
      val user = TelegramChat(message.chat.id)
      activeUsers.get(user).foreach { talk =>
        message.text.foreach(talk ! Dialog.PushMessageToTalk(user, _))
      }

    case Update(num, Some(message), _, _, _, _, _, _, _, _)  if isNotInDialog(message.chat.id) =>
      request(helpMessage(message.chat.id))

    case Update(num, Some(message), _, _, _, _, _, _, _, _) => request(helpMessage(message.chat.id))


    case Endpoint.AddTargetTalkForUserWithChat(user: TelegramChat, talk: ActorRef) => activeUsers += user -> talk
    case Endpoint.RemoveTargetTalkForUserWithChat(user: TelegramChat) => activeUsers -= user

    case Endpoint.DeliverMessageToUser(TelegramChat(id), text, _) =>
      request(SendMessage(Left(id), text, Some(ParseMode.Markdown)))
  }

  private val activeUsers = mutable.Map[TelegramChat, ActorRef]()

  private def isInDialog(chatId: Long) = activeUsers.keySet.contains(TelegramChat(chatId))
  private def isNotInDialog(chatId: Long) = !isInDialog(chatId)

  private def helpMessage(chatId: Long) = SendMessage(Left(chatId),
    """
      |*Help message*
      |
      |Use:
      |
      |- /begin for start talk
      |- /end for end talk
      |- /help for help
      |
      |[link](http://vkurselife.com/wp-content/uploads/2016/05/b5789b.jpg)
    """.stripMargin, Some(ParseMode.Markdown))
}

object TelegramEndpoint {
  def props(talkConstructor: ActorRef): Props = Props(new TelegramEndpoint(talkConstructor))

  private case object Command {
    def unapply(message: Update): Option[(Chat, String)] = {
      if (message.message.exists(_.text.exists(_.startsWith("/")))) Some((message.message.get.chat, message.message.get.text.get)) else None
    }
  }

  case class SetGateway(gate: RequestHandler)
}