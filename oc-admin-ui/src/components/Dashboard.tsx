/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useState, useEffect } from 'react'
import { 
  BarChart, 
  Bar, 
  XAxis, 
  YAxis, 
  CartesianGrid, 
  Tooltip, 
  ResponsiveContainer,
  AreaChart,
  Area
} from 'recharts'
import { 
  PlayCircle, 
  CheckCircle2, 
  AlertCircle, 
  Database,
  Server,
  Zap,
  Activity
} from 'lucide-react'
import { statusApi, jobApi } from '../lib/api'

export default function Dashboard() {
  const [throughputData, setThroughputData] = useState<any[]>([])
  const [systemStatus, setSystemStatus] = useState<Record<string, string>>({
    postgres: 'DOWN',
    redis: 'DOWN',
    ollama: 'DOWN',
    system: 'UNKNOWN'
  })
  const [statsData, setStatsData] = useState({
    activeJobs: 0,
    indexedDocs: 0,
    serviceAlerts: 0,
    vectorStoreSize: '0 MB'
  })

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [statusRes, throughputRes, jobsRes] = await Promise.all([
          statusApi.getSystemStatus(),
          statusApi.getThroughput(),
          jobApi.getAll()
        ])
        
        setSystemStatus(statusRes.data)
        setThroughputData(throughputRes.data)

        const jobs = jobsRes.data || []
        const activeCount = jobs.filter((j: any) => j.status === 'Running').length
        const totalDocs = jobs.reduce((sum: number, j: any) => sum + (j.documents || 0), 0)
        const errorCount = jobs.filter((j: any) => j.status === 'Error').length

        setStatsData({
          activeJobs: activeCount,
          indexedDocs: totalDocs,
          serviceAlerts: errorCount,
          vectorStoreSize: `${(totalDocs * 0.005).toFixed(1)} MB`
        })
      } catch (err) {
        console.error("Failed to fetch dashboard data:", err)
      }
    }
    
    fetchData()
    const interval = setInterval(fetchData, 4000)
    return () => clearInterval(interval)
  }, [])

  const stats = [
    { label: 'Active Jobs', value: statsData.activeJobs.toString(), icon: PlayCircle, color: 'text-cyan-500', bg: 'bg-cyan-500/10' },
    { label: 'Indexed Documents', value: statsData.indexedDocs.toLocaleString(), icon: CheckCircle2, color: 'text-green-500', bg: 'bg-green-500/10' },
    { label: 'Service Alerts', value: statsData.serviceAlerts.toString(), icon: AlertCircle, color: statsData.serviceAlerts > 0 ? 'text-red-500' : 'text-slate-400', bg: statsData.serviceAlerts > 0 ? 'bg-red-500/10' : 'bg-slate-400/10' },
    { label: 'Estimated Vector Size', value: statsData.vectorStoreSize, icon: Database, color: 'text-blue-500', bg: 'bg-blue-500/10' },
  ]

  return (
    <div className="space-y-8 animate-in fade-in duration-500">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold tracking-tight">Dashboard Overview</h1>
        <div className="flex gap-2">
           <div className={`flex items-center gap-2 px-3 py-1.5 rounded-md border text-xs font-medium transition-colors ${
             systemStatus.postgres === 'UP' ? 'bg-green-500/10 border-green-500/20 text-green-500' : 'bg-red-500/10 border-red-500/20 text-red-500'
           }`}>
              <Server className="w-3.5 h-3.5" />
              <span>Postgres: {systemStatus.postgres}</span>
           </div>
           <div className={`flex items-center gap-2 px-3 py-1.5 rounded-md border text-xs font-medium transition-colors ${
             systemStatus.redis === 'UP' ? 'bg-green-500/10 border-green-500/20 text-green-500' : 'bg-red-500/10 border-red-500/20 text-red-500'
           }`}>
              <Zap className="w-3.5 h-3.5" />
              <span>Redis: {systemStatus.redis}</span>
           </div>
           <div className={`flex items-center gap-2 px-3 py-1.5 rounded-md border text-xs font-medium transition-colors ${
             systemStatus.ollama === 'UP' ? 'bg-green-500/10 border-green-500/20 text-green-500' : 'bg-red-500/10 border-red-500/20 text-red-500'
           }`}>
              <Activity className="w-3.5 h-3.5" />
              <span>Ollama: {systemStatus.ollama}</span>
           </div>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {stats.map((stat) => (
          <div key={stat.label} className="card-container flex items-center gap-4">
            <div className={`p-3 rounded-lg ${stat.bg}`}>
              <stat.icon className={`w-6 h-6 ${stat.color}`} />
            </div>
            <div>
              <p className="text-sm text-muted font-medium">{stat.label}</p>
              <h3 className="text-2xl font-bold">{stat.value}</h3>
            </div>
          </div>
        ))}
      </div>

      {/* Charts Section */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="card-container">
          <h3 className="text-lg font-semibold mb-6">Ingestion Throughput (docs/sec)</h3>
          <div className="h-[300px] w-full">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={throughputData}>
                <defs>
                  <linearGradient id="colorDocs" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#06b6d4" stopOpacity={0.3}/>
                    <stop offset="95%" stopColor="#06b6d4" stopOpacity={0}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1e293b" />
                <XAxis dataKey="name" stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                <YAxis stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                <Tooltip 
                   contentStyle={{ backgroundColor: '#0f172a', border: '1px solid #1e293b', borderRadius: '8px' }}
                   itemStyle={{ color: '#06b6d4' }}
                />
                <Area type="monotone" dataKey="docs" stroke="#06b6d4" fillOpacity={1} fill="url(#colorDocs)" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card-container">
          <h3 className="text-lg font-semibold mb-6">Connector Performance</h3>
          <div className="h-[300px] w-full">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={throughputData}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1e293b" />
                <XAxis dataKey="name" stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                <YAxis stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                <Tooltip 
                  contentStyle={{ backgroundColor: '#0f172a', border: '1px solid #1e293b', borderRadius: '8px' }}
                />
                <Bar dataKey="docs" fill="#3b82f6" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>
    </div>
  )
}
