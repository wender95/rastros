import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { MarcaEmpresa, useEmpresa } from '../componentes/Marca'
import { Aviso } from '../componentes/Ui'

export default function Login() {
  const { entrar } = useAuth()
  const [usuario, setUsuario] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const empresa = useEmpresa()

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await entrar(usuario, senha)
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao entrar.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="login-tela">
      <div className="login-caixa">
        <h1 className="login-marca">
          {/* A marca é a da empresa que usa o sistema nesta instalação. */}
          <MarcaEmpresa empresa={empresa} />
        </h1>
        <p className="subtitulo">Rastreabilidade de Ordens de Serviço</p>

        {erro && <Aviso tipo="erro">{erro}</Aviso>}

        <form onSubmit={enviar}>
          <div className="campo">
            <label htmlFor="usuario">Usuário</label>
            <input
              id="usuario"
              type="text"
              value={usuario}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoComplete="username"
              onChange={(e) => setUsuario(e.target.value)}
              required
            />
          </div>
          <div className="campo">
            <label htmlFor="senha">Senha</label>
            <input
              id="senha"
              type="password"
              value={senha}
              autoComplete="current-password"
              onChange={(e) => setSenha(e.target.value)}
              required
            />
          </div>
          <button className="botao botao-grande botao-bloco" disabled={enviando}>
            {enviando ? 'Entrando...' : 'Entrar'}
          </button>
        </form>
      </div>
    </div>
  )
}
