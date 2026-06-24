import { useState, useEffect } from 'react'
import { 
  LayoutDashboard, 
  PlayCircle, 
  Plug2, 
  Activity, 
  Settings, 
  Menu,
  X,
  Search,
  ChevronRight
} from 'lucide-react'
import Dashboard from './components/Dashboard'
import JobTable from './components/JobTable'
import ConnectorForm from './components/ConnectorForm'
import { statusApi } from './lib/api'

type View = 'dashboard' | 'jobs' | 'connectors' | 'logs' | 'settings'

export default function App() {
  const [activeView, setActiveView] = useState<View>('dashboard')
  const [isSidebarOpen, setIsSidebarOpen] = useState(true)
  const [systemStatus, setSystemStatus] = useState<Record<string, string>>({
    postgres: 'DOWN',
    redis: 'DOWN',
    ollama: 'DOWN',
    system: 'UNKNOWN'
  })
  const [logs, setLogs] = useState<string[]>([])

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const res = await statusApi.getSystemStatus()
        setSystemStatus(res.data)
      } catch (err) {
        console.error("Failed to fetch system status:", err)
        setSystemStatus({
          postgres: 'DOWN',
          redis: 'DOWN',
          ollama: 'DOWN',
          system: 'UNREACHABLE'
        })
      }
    }
    fetchStatus()
    const statusInterval = setInterval(fetchStatus, 5000)
    return () => clearInterval(statusInterval)
  }, [])

  useEffect(() => {
    if (activeView !== 'logs') return
    const fetchLogs = async () => {
      try {
        const res = await statusApi.getLogs()
        setLogs(res.data)
      } catch (err) {
        console.error("Failed to fetch logs:", err)
      }
    }
    fetchLogs()
    const logsInterval = setInterval(fetchLogs, 2000)
    return () => clearInterval(logsInterval)
  }, [activeView])

  const getLogLineColor = (line: string) => {
    if (line.includes("INFO:")) return "text-blue-400";
    if (line.includes("DEBUG:")) return "text-slate-500";
    if (line.includes("WARN:")) return "text-yellow-400";
    if (line.includes("SUCCESS:")) return "text-green-400";
    if (line.includes("ERROR:") || line.includes("FAILED:")) return "text-red-400";
    return "text-slate-300";
  }

  const menuItems = [
    { id: 'dashboard', label: 'Dashboard', icon: LayoutDashboard },
    { id: 'jobs', label: 'Job Management', icon: PlayCircle },
    { id: 'connectors', label: 'Connectors', icon: Plug2 },
    { id: 'logs', label: 'Real-time Logs', icon: Activity },
    { id: 'settings', label: 'Settings', icon: Settings },
  ]

  return (
    <div className="flex h-screen bg-background text-foreground overflow-hidden">
      {/* Sidebar */}
      <aside className={`
        ${isSidebarOpen ? 'w-64' : 'w-20'} 
        transition-all duration-300 ease-in-out
        bg-card border-r border-border flex flex-col
      `}>
        <div className="p-6 flex items-center gap-3">
          <div className="bg-primary p-2 rounded-lg">
            <Search className="w-6 h-6 text-primary-foreground" />
          </div>
          {isSidebarOpen && (
            <span className="font-bold text-lg tracking-tight whitespace-nowrap">
              Spring-Manifold
            </span>
          )}
        </div>

        <nav className="flex-1 px-4 py-6 space-y-2">
          {menuItems.map((item) => (
            <button
              key={item.id}
              onClick={() => setActiveView(item.id as View)}
              className={`
                w-full flex items-center gap-3 px-3 py-2 rounded-md transition-colors
                ${activeView === item.id 
                  ? 'bg-primary/10 text-primary border border-primary/20' 
                  : 'text-muted hover:bg-secondary hover:text-foreground'}
              `}
            >
              <item.icon className="w-5 h-5 min-w-[20px]" />
              {isSidebarOpen && <span className="font-medium">{item.label}</span>}
            </button>
          ))}
        </nav>

        <div className="p-4 border-t border-border">
          <button 
            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
            className="w-full flex items-center justify-center p-2 rounded-md hover:bg-secondary"
          >
            {isSidebarOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col overflow-hidden">
        <header className="h-16 border-b border-border flex items-center justify-between px-8 bg-card/50 backdrop-blur-sm">
          <div className="flex items-center gap-2 text-muted text-sm">
            <span>Admin</span>
            <ChevronRight className="w-4 h-4" />
            <span className="text-foreground font-medium capitalize">{activeView}</span>
          </div>
          
          <div className="flex items-center gap-4">
            <div className={`flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium border ${
              systemStatus.system === 'HEALTHY'
                ? 'bg-green-500/10 text-green-500 border-green-500/20'
                : 'bg-red-500/10 text-red-500 border-red-500/20'
            }`}>
              <div className={`w-2 h-2 rounded-full animate-pulse ${
                systemStatus.system === 'HEALTHY' ? 'bg-green-500' : 'bg-red-500'
              }`} />
              System: {systemStatus.system}
            </div>
            <div className="w-8 h-8 rounded-full bg-secondary border border-border flex items-center justify-center">
              <span className="text-xs font-bold">PL</span>
            </div>
          </div>
        </header>

        <div className="flex-1 overflow-y-auto p-8 custom-scrollbar">
          {activeView === 'dashboard' && <Dashboard />}
          {activeView === 'jobs' && <JobTable />}
          {activeView === 'connectors' && <ConnectorForm />}
          {activeView === 'logs' && (
            <div className="h-full flex flex-col gap-4">
               <h1 className="text-2xl font-bold">Real-time Activity Logs</h1>
               <div className="flex-1 bg-slate-900 rounded-lg p-4 font-mono text-sm overflow-y-auto border border-border text-slate-300 flex flex-col gap-1.5">
                  {logs.length === 0 ? (
                    <p className="text-slate-500 italic">No log entries available.</p>
                  ) : (
                    logs.map((log, index) => (
                      <p key={index} className={getLogLineColor(log)}>
                        {log}
                      </p>
                    ))
                  )}
               </div>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
