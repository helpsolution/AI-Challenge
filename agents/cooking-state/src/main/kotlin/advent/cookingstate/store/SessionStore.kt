package advent.cookingstate.store

import advent.cookingstate.agent.CookingSession

interface SessionStore {
    fun create(): CookingSession
    fun find(id: String): CookingSession?
    /** Возвращает false, если сессия успела измениться с момента чтения. */
    fun save(previousVersion: Int, session: CookingSession): Boolean
}
