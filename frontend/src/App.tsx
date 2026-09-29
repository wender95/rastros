import { useEffect, useState, type ReactElement } from 'react'
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { pode, rotuloPerfil, telaInicial } from './auth/acessos'
import type { Area } from './auth/acessos'
import { useAuth } from './auth/AuthContext'
import { setoresDe } from './auth/setorAtivo'
import { Carregando, rotuloSetor } from './componentes/Ui'
import { FaixaAgindoComo, SeletorAgirComo } from './componentes/AgirComo'
import { MarcaEmpresa, useEmpresa } from './componentes/Marca'
import Admin from './paginas/Admin'
import AgendaSimples from './paginas/AgendaSimples'
import Consulta from './paginas/Consulta'
import FluxoDetalhe from './paginas/FluxoDetalhe'
import Login from './paginas/Login'
import MinhaAgenda from './paginas/MinhaAgenda'
import Movimentar from './paginas/Movimentar'
import NovaOrdem from './paginas/NovaOrdem'
import OrdemDetalhe from './paginas/OrdemDetalhe'
import PatioPrateleira from './paginas/PatioPrateleira'
import Painel from './paginas/Painel'
import Produtividade from './paginas/Produtividade'
import RelatorioSemanal from './paginas/RelatorioSemanal'
import TrocarSenha from './paginas/TrocarSenha'
import { TvAcabamento, TvAdesivadores } from './paginas/TelasDeTv'

/** Itens do menu, na ordem em que aparecem; cada um só para quem tem a área. */
const MENU: { area: Area; rota: string; rotulo: string }[] = [
  { area: 'movimentar', rota: '/movimentar', rotulo: 'Meu setor' },
  { area: 'minhaAgenda', rota: '/minha-agenda', rotulo: 'Minha agenda' },
  { area: 'painel', rota: '/painel', rotulo: 'Painel' },
  // As duas telas de TV da fábrica: a agenda do dia dos adesivadores e o Acabamento.
  { area: 'tvAdesivadores', rota: '/tv/adesivadores', rotulo: 'TV Adesivadores' },
  { area: 'tvAcabamento', rota: '/tv/acabamento', rotulo: 'TV Acabamento' },
  { area: 'agenda', rota: '/agenda', rotulo: 'Agenda' },
  { area: 'produtividade', rota: '/produtividade', rotulo: 'Produtividade' },
  { area: 'relatorio', rota: '/relatorio-semanal', rotulo: 'Relatório' },
  { area: 'patioPrateleira', rota: '/patio-prateleira', rotulo: 'Pátio e prateleira' },
  { area: 'consulta', rota: '/consulta', rotulo: 'Consultar OS' },
  // As OS vêm do ERP pela extensão; abrir por aqui é a exceção.
  { area: 'criarOs', rota: '/ordens/nova', rotulo: 'OS manual' },
]

export default function App() {
  const { usuario, carregando, sair } = useAuth()
  const location = useLocation()
  /** No celular, o menu e os dados da pessoa ficam recolhidos atrás do botão ☰. */
  const [menuAberto, setMenuAberto] = useState(false)
  // Trocou de tela: a gaveta do menu fecha sozinha.
  useEffect(() => setMenuAberto(false), [location.pathname])
  const empresa = useEmpresa()
  // A aba do navegador leva o nome da empresa que usa o sistema.
  useEffect(() => {
    if (empresa) document.title = empresa.nome
  }, [empresa])

  if (carregando) return <Carregando texto="Carregando sessão..." />
  if (!usuario) return <Login />
  // Senha provisoria: nada alem da troca. O servidor tambem recusa o resto.
  // (O administrador agindo por alguém com senha provisória não troca a senha da pessoa.)
  if (usuario.trocarSenha && !usuario.agindoPor) return <TrocarSenha obrigatoria />

  const inicio = telaInicial(usuario)
  /** A tela, se o perfil tem a área; senão volta para a tela inicial dele. */
  const so = (area: Area, tela: ReactElement) => (pode(usuario, area) ? tela : <Navigate to={inicio} replace />)
  const administra = pode(usuario, 'administracao')

  return (
    <div className="app">
      <header className="topo">
        <div className="topo-interno">
          <div className="marca">
            {/* A marca é a da empresa que usa o sistema nesta instalação. */}
            <MarcaEmpresa empresa={empresa} />
          </div>

          {/* Só aparece no celular: abre e fecha a gaveta com o menu e os dados da pessoa. */}
          <button
            className="topo-menu-botao"
            aria-label={menuAberto ? 'Fechar menu' : 'Abrir menu'}
            aria-expanded={menuAberto}
            onClick={() => setMenuAberto((a) => !a)}
          >
            {menuAberto ? '✕' : '☰'}
          </button>

          {/* No PC a gaveta não existe (display: contents): menu e pessoa ficam na barra. */}
          <div className={`topo-gaveta${menuAberto ? ' aberta' : ''}`}>
          <nav className="nav">
            {MENU.filter((m) => pode(usuario, m.area)).map((m) => (
              <NavLink key={m.rota} to={m.rota} className={({ isActive }) => (isActive ? 'ativo' : '')}>
                {m.rotulo}
              </NavLink>
            ))}
            {pode(usuario, 'usuarios') && (
              <NavLink to="/admin" className={({ isActive }) => (isActive ? 'ativo' : '')}>
                {administra ? 'Administração' : 'Usuários'}
              </NavLink>
            )}
          </nav>

          <div className="usuario-topo">
            <SeletorAgirComo usuario={usuario} />
            <div>
              <strong>{usuario.nome}</strong>
              <small>
                {rotuloPerfil(usuario.perfil)}
                {setoresDe(usuario).length ? ` · ${setoresDe(usuario).map((s) => rotuloSetor(s.nome)).join(' / ')}` : ''}
              </small>
            </div>
            <NavLink to="/trocar-senha" className="link-topo">
              Trocar senha
            </NavLink>
            <button className="botao botao-secundario" onClick={sair}>
              Sair
            </button>
          </div>
          </div>
        </div>
        {/* Dentro do topo fixo: rolando a tela, o aviso e o "Agir como" continuam à vista. */}
        <FaixaAgindoComo usuario={usuario} />
      </header>

      {/* A agenda usa a tela inteira: todas as colunas à vista, o mais largas possível. */}
      <main
        className={`conteudo${location.pathname === '/agenda' || location.pathname.startsWith('/tv/') ? ' conteudo-largo' : ''}`}
      >
        <Routes>
          <Route path="/" element={<Navigate to={inicio} replace />} />
          <Route path="/movimentar" element={so('movimentar', <Movimentar />)} />
          <Route path="/fila" element={<Navigate to={inicio} replace />} />
          <Route path="/minha-agenda" element={so('minhaAgenda', <MinhaAgenda />)} />
          <Route path="/painel" element={so('painel', <Painel />)} />
          <Route path="/tv/adesivadores" element={so('tvAdesivadores', <TvAdesivadores />)} />
          <Route path="/tv/acabamento" element={so('tvAcabamento', <TvAcabamento />)} />
          <Route
            path="/sem-acesso"
            element={
              <div className="cartao">
                <h1>Nenhuma seção liberada</h1>
                <p className="subtitulo">Peça ao administrador para liberar as seções que você usa.</p>
              </div>
            }
          />
          <Route path="/agenda" element={so('agenda', <AgendaSimples />)} />
          {/* Quem tinha um endereço antigo salvo (a agenda simples, a agenda por horário) continua chegando na agenda. */}
          <Route path="/agenda-simples" element={<Navigate to="/agenda" replace />} />
          <Route path="/agenda-horarios" element={<Navigate to="/agenda" replace />} />
          <Route path="/produtividade" element={so('produtividade', <Produtividade />)} />
          <Route path="/relatorio-semanal" element={so('relatorio', <RelatorioSemanal />)} />
          <Route path="/patio-prateleira" element={so('patioPrateleira', <PatioPrateleira />)} />
          <Route path="/consulta" element={so('consulta', <Consulta />)} />
          <Route path="/ordens/nova" element={so('criarOs', <NovaOrdem />)} />
          <Route path="/ordens/:id" element={so('consulta', <OrdemDetalhe />)} />
          <Route path="/fluxos/:id" element={so('consulta', <FluxoDetalhe />)} />
          <Route path="/admin" element={so('usuarios', <Admin completo={administra} />)} />
          <Route path="/trocar-senha" element={<TrocarSenha />} />
          <Route path="*" element={<Navigate to={inicio} replace />} />
        </Routes>
      </main>
    </div>
  )
}
