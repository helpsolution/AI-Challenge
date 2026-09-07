package advent.day6.agent

/** Агент один и отвечает по одному запросу за раз: параллельный ход сломал бы ему память. */
class AgentBusyException(name: String) :
    RuntimeException("$name сейчас отвечает на другой вопрос — подождите, пока закончит")
