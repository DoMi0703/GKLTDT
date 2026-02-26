import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GraphDFSServer
 * - Serve UI tại GET /
 * - API GET /dfs?start=<v>&format=json  (JSON output for UI)
 * - Backwards-compatible text output at /dfs?start=<v>
 */
public class GraphDFSServer {

    private static final int N = 9; // đỉnh 0..8
    @SuppressWarnings("unchecked")
    private final List<Integer>[] adj = (List<Integer>[]) new ArrayList[N];

    public GraphDFSServer() {
        for (int i = 0; i < N; i++) adj[i] = new ArrayList<>();
        buildDefaultGraph();
    }

    private void buildDefaultGraph() {
        // Xây đồ thị theo Hình 1.1 (vô hướng) — sửa nếu đề yêu cầu khác
        addEdge(0,1); addEdge(0,7);
        addEdge(1,2); addEdge(1,7);
        addEdge(2,3); addEdge(2,8); addEdge(2,5);
        addEdge(3,4); addEdge(3,5);
        addEdge(4,5);
        addEdge(5,6);
        addEdge(6,7); addEdge(6,8);
        addEdge(7,8);
        for (int i = 0; i < N; i++) Collections.sort(adj[i]);
    }

    private void addEdge(int u, int v) {
        adj[u].add(v);
        adj[v].add(u);
    }

    // ---------------- JSON-producing DFS (iterative) ----------------
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String adjacencyJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (int i = 0; i < N; i++) {
            sb.append("\"").append(i).append("\":");
            sb.append("[");
            for (int j = 0; j < adj[i].size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(adj[i].get(j));
            }
            sb.append("]");
            if (i < N-1) sb.append(",");
        }
        sb.append("}");
        return sb.toString();
    }

    private String dfsIterativeJson(int start) {
        StringBuilder out = new StringBuilder();
        boolean[] visited = new boolean[N];
        Deque<Integer> stack = new ArrayDeque<>();
        List<Map<String,Object>> steps = new ArrayList<>();

        stack.push(start);
        // record initial step
        steps.add(stepMap("PUSH", start, stack, visited));
        List<Integer> visitedOrder = new ArrayList<>();
        while (!stack.isEmpty()) {
            int v = stack.peek();
            if (!visited[v]) {
                visited[v] = true;
                visitedOrder.add(v); // GHI NHẬN THỨ TỰ NGAY KHI VISIT
                steps.add(stepMap("VISIT", v, stack, visited));
            }

            Integer next = null;
            for (int u : adj[v]) {
                if (!visited[u] && !stack.contains(u)) {
                    next = u; break;
                }
            }

            if (next != null) {
                stack.push(next);
                steps.add(stepMap("PUSH", next, stack, visited));
            } else {
                int popped = stack.pop();
                steps.add(stepMap("POP", popped, stack, visited));
            }
        }

        // build JSON
        out.append("{");
        out.append("\"adj\":").append(adjacencyJson()).append(",");
        out.append("\"start\":").append(start).append(",");
        out.append("\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            Map<String,Object> m = steps.get(i);
            out.append("{");
            out.append("\"action\":\"").append(escapeJson((String)m.get("action"))).append("\",");
            out.append("\"node\":").append(m.get("node")).append(",");
            out.append("\"stack\":").append(listToJson((List<Integer>)m.get("stack"))).append(",");
            out.append("\"visited\":").append(boolArrayToJson((boolean[])m.get("visited")));
            out.append("}");
            if (i < steps.size()-1) out.append(",");
        }
        out.append("],");
        out.append("\"visitedOrder\":").append(listToJson(visitedOrder));
        out.append("}");
        return out.toString();
    }

    // Helper: represent a step as a map
    private Map<String,Object> stepMap(String action, int node, Deque<Integer> stack, boolean[] visited) {
        Map<String,Object> m = new HashMap<>();
        m.put("action", action);
        m.put("node", node);
        // snapshot stack as list (top at index 0 to show like [top,...])
        List<Integer> stackSnapshot = new ArrayList<>(stack);
        m.put("stack", stackSnapshot);
        // copy visited
        boolean[] visitedCopy = Arrays.copyOf(visited, visited.length);
        m.put("visited", visitedCopy);
        return m;
    }

    private String listToJson(List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private String boolArrayToJson(boolean[] arr) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i] ? "true" : "false");
        }
        sb.append("]");
        return sb.toString();
    }

    // ---------------- HTTP server & handlers ----------------

    private void startHttpServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Serve UI at "/"
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String html = indexHtml();
                byte[] resp = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });

        // Serve DFS output (text or JSON) at "/dfs"
        server.createContext("/dfs", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                URI uri = exchange.getRequestURI();
                String query = uri.getQuery();
                Map<String, String> params = parseQuery(query);
                String startStr = params.get("start");
                String format = params.getOrDefault("format", "");
                String response;
                if (startStr == null) {
                    response = "Giao thức: /dfs?start=<đỉnh 0..8>&format=json\n";
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                int start;
                try {
                    start = Integer.parseInt(startStr);
                } catch (NumberFormatException ex) {
                    response = "Lỗi: start phải là số nguyên 0.." + (N-1) + "\n";
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, response.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                if (start < 0 || start >= N) {
                    response = "Lỗi: start ngoài phạm vi 0.." + (N-1) + "\n";
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, response.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                if ("json".equalsIgnoreCase(format)) {
                    response = dfsIterativeJson(start);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    byte[] resp = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else {
                    // fallback: plain text (compat)
                    StringBuilder sb = new StringBuilder();
                    sb.append(adjacencyText()).append("\n");
                    sb.append(dfsIterativeTrace(start)).append("\n");
                    response = sb.toString();
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    byte[] resp = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
            }
        });

        server.setExecutor(null);
        System.out.println("Server started at http://0.0.0.0:" + port);
        server.start();
    }

    private String adjacencyText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Danh sách kề (đồ thị vô hướng):\n");
        for (int i = 0; i < N; i++) {
            sb.append(i).append(": ").append(adj[i]).append("\n");
        }
        return sb.toString();
    }

    // plain text trace (kept for compatibility)
    public String dfsIterativeTrace(int start) {
        StringBuilder out = new StringBuilder();
        boolean[] visited = new boolean[N];
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        out.append(String.format("Bắt đầu DFS từ đỉnh %d\n", start));
        out.append("Stack ban đầu: " + stack + "\n\n");

        while (!stack.isEmpty()) {
            int v = stack.peek();
            if (!visited[v]) {
                visited[v] = true;
                out.append("VISIT " + v + " (đánh dấu visited)\n");
            }
            Integer next = null;
            for (int u : adj[v]) {
                if (!visited[u] && !stack.contains(u)) {
                    next = u; break;
                }
            }
            if (next != null) {
                stack.push(next);
                out.append(String.format("PUSH %d (kề của %d). Stack: %s\n", next, v, stack));
            } else {
                int popped = stack.pop();
                out.append(String.format("POP %d (không còn neighbor chưa thăm). Stack: %s\n", popped, stack));
            }
            out.append("\n");
        }
        out.append("DFS hoàn tất. Thứ tự đỉnh đã thăm: ");
        boolean first = true;
        for (int i = 0; i < N; i++) if (visited[i]) {
            if (!first) out.append(", ");
            out.append(i);
            first = false;
        }
        out.append("\n");
        return out.toString();
    }

    // ---------------- simple UI HTML (Vietnamese) ----------------
    private String indexHtml() {
        // embedded minimal CSS + JS (fetch /dfs?start=...&format=json)
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang='vi'>\n<head>\n<meta charset='utf-8'>\n");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>\n");
        sb.append("<title>Demo DFS — Bài giữa kỳ</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:Inter,system-ui,Segoe UI,Roboto,Arial;margin:18px;background:#f7fafc;color:#111}\n");
        sb.append(".container{max-width:1000px;margin:0 auto;background:#fff;padding:18px;border-radius:10px;box-shadow:0 6px 24px rgba(0,0,0,0.08)}\n");
        sb.append("h1{font-size:20px;margin:0 0 10px}\n");
        sb.append(".row{display:flex;gap:16px}\n");
        sb.append(".col{flex:1}\n");
        sb.append(".panel{background:#fbfbff;padding:12px;border-radius:8px;border:1px solid #eef2ff}\n");
        sb.append("label{display:block;margin-bottom:6px;font-weight:600}\n");
        sb.append("input[type=number]{width:100%;padding:8px;border-radius:6px;border:1px solid #d7d7e6}\n");
        sb.append("button{padding:8px 12px;border-radius:8px;border:0;background:#2563eb;color:white;cursor:pointer}\n");
        sb.append("button.secondary{background:#6b7280}\n");
        sb.append(".nodes{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}\n");
        sb.append(".node{width:40px;height:40px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:#e6eefc;color:#0b2a66;font-weight:700}\n");
        sb.append(".node.visited{background:#16a34a;color:white}\n");
        sb.append(".stack{min-height:40px;border-radius:6px;padding:8px;background:#fff;border:1px solid #e6e6ef}\n");
        sb.append(".steps{max-height:360px;overflow:auto;padding:8px;background:#fff;border-radius:6px;border:1px solid #e6e6ef}\n");
        sb.append(".step{padding:6px;border-bottom:1px dashed #eee}\n");
        sb.append(".step.current{background:linear-gradient(90deg, rgba(37,99,235,0.06), transparent)}\n");
        sb.append(".meta{font-size:13px;color:#374151;margin-bottom:8px}\n");
        sb.append("</style>\n</head>\n<body>\n<div class='container'>\n  <h1>DEMO DFS (Đồ án giữa kỳ)</h1>\n  <div class='meta'>Đồ thị: 9 đỉnh (0..8). Nhập đỉnh bắt đầu, bấm Chạy. </div>\n  <div class='row'>\n    <div class='col'>\n      <div class='panel'>\n        <label>Đỉnh bắt đầu (0..8)</label>\n        <input id='start' type='number' min='0' max='8' value='0'>\n        <div style='margin-top:10px;display:flex;gap:8px'>\n          <button id='run'>Chạy DFS</button>\n          <button id='stepPrev' class='secondary'>⟵ Bước trước</button>\n          <button id='stepNext' class='secondary'>Bước sau ⟶</button>\n          <button id='play' class='secondary'>▶ Play</button>\n          <button id='stop' class='secondary'>■ Stop</button>\n        </div>\n        <div style='margin-top:12px'>\n          <div class='meta'>Stack (đỉnh trên cùng ở bên trái):</div>\n          <div id='stack' class='stack'></div>\n        </div>\n      </div>\n      <div style='margin-top:12px' class='panel'>\n        <div class='meta'>Đỉnh (màu xanh: đã thăm)</div>\n        <div id='nodes' class='nodes'></div>\n      </div>\n    </div>\n    <div class='col'>\n      <div class='panel'>\n        <div class='meta'>Danh sách kề</div>\n        <pre id='adj' style='white-space:pre-wrap;margin:0'></pre>\n      </div>\n      <div style='margin-top:12px' class='panel'>\n        <div class='meta'>Các bước (nhấn Play để tự chạy)</div>\n        <div id='steps' class='steps'></div>\n      </div>\n    </div>\n  </div>\n  <div style='margin-top:12px' class='panel'>\n    <div class='meta'>Kết quả nhanh</div>\n    <div id='result' style='white-space:pre-wrap;font-family:monospace'></div>\n <div id='visitedOrder' style='margin-top:8px;font-weight:600;color:#0b2a66'></div>\n </div>\n</div>\n\n<script>\n(function(){\n  const startInput = document.getElementById('start');\n  const runBtn = document.getElementById('run');\n  const stepsEl = document.getElementById('steps');\n  const nodesEl = document.getElementById('nodes');\n  const stackEl = document.getElementById('stack');\n  const adjEl = document.getElementById('adj');\n  const resultEl = document.getElementById('result');\n  const prevBtn = document.getElementById('stepPrev');\n  const nextBtn = document.getElementById('stepNext');\n  const playBtn = document.getElementById('play');\n  const stopBtn = document.getElementById('stop');\n\n  let jsonData = null;\n  let index = 0;\n  let timer = null;\n\n  function renderNodes(visitedArr){\n    nodesEl.innerHTML = '';\n    visitedArr = visitedArr || Array(9).fill(false);\n    for(let i=0;i<9;i++){\n      const d = document.createElement('div'); d.className='node' + (visitedArr[i] ? ' visited' : ''); d.textContent = i; nodesEl.appendChild(d);\n    }\n  }\n\n  function renderAdj(adj){\n    let text='';\n    for(const k in adj){ text += k + ': [' + adj[k].join(', ') + ']\\n'; }\n    adjEl.textContent = text;\n  }\n\n  function renderStack(arr){\n    stackEl.innerHTML = '';\n    if(!arr || arr.length===0){ stackEl.textContent='(rỗng)'; return; }\n    // show top at left\n    const ul = document.createElement('div'); ul.style.display='flex'; ul.style.gap='8px';\n    for(let i=0;i<arr.length;i++){\n      const s = document.createElement('div'); s.style.padding='6px 10px'; s.style.borderRadius='6px'; s.style.background='#eef2ff'; s.textContent = arr[i]; ul.appendChild(s);\n    }\n    stackEl.appendChild(ul);\n  }\n\n  function renderStepsList(steps){\n    stepsEl.innerHTML = '';\n    for(let i=0;i<steps.length;i++){\n      const s = document.createElement('div'); s.className='step'; s.dataset.i = i;\n      s.textContent = (i+1) + '. ' + steps[i].action + ' ' + steps[i].node + '  | stack: [' + steps[i].stack.join(',') + ']';\n      s.onclick = ()=>{ setIndex(i); }; \n      stepsEl.appendChild(s);\n    }\n  }\n\n  function applyStep(i){\n    if(!jsonData) return;\n    const st = jsonData.steps[i];\n    // update nodes\n    renderNodes(st.visited);\n    renderStack(st.stack);\n    // highlight current step\n    Array.from(stepsEl.children).forEach((c, idx)=> c.classList.toggle('current', idx===i));\n    // update result summary\n    resultEl.textContent = 'Bước ' + (i+1) + ' — ' + st.action + ' ' + st.node + '\\n\\n' + 'Stack hiện tại: [' + st.stack.join(',') + ']';\n  }\n\n  function setIndex(i){ index = Math.max(0, Math.min(i, jsonData.steps.length-1)); applyStep(index); }\n\n  function fetchAndRender(){\n    const v = Number(startInput.value);\n    fetch('/dfs?start=' + v + '&format=json').then(r=>r.json()).then(j=>{\n      jsonData = j;\n      renderAdj(j.adj);\n      renderNodes();\n      renderStack([]);\n      renderStepsList(j.steps);\n      setIndex(0);\n    document.getElementById('visitedOrder').textContent = 'Thứ tự DFS: ' + (j.visitedOrder || []).join(' → ');      }).catch(e=>{\n      alert('Lỗi khi gọi API: ' + e);\n    });\n  }\n\n  runBtn.addEventListener('click', ()=>{ fetchAndRender(); });\n  prevBtn.addEventListener('click', ()=>{ if(jsonData) setIndex(index-1); });\n  nextBtn.addEventListener('click', ()=>{ if(jsonData) setIndex(index+1); });\n  playBtn.addEventListener('click', ()=>{ if(!jsonData) return; if(timer) clearInterval(timer); timer = setInterval(()=>{ if(index < jsonData.steps.length-1) setIndex(index+1); else clearInterval(timer); }, 700); });\n  stopBtn.addEventListener('click', ()=>{ if(timer) { clearInterval(timer); timer = null; } });\n\n  // auto load default\n  fetchAndRender();\n})();\n</script>\n</body>\n</html>\n");
        return sb.toString();
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        String[] parts = q.split("&");
        for (String p : parts) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    public static void main(String[] args) throws Exception {
        GraphDFSServer app = new GraphDFSServer();
        String portEnv = System.getenv("PORT");
        int port = 8080;
        if (portEnv != null) {
            try { port = Integer.parseInt(portEnv); } catch (Exception ignored) {}
        }
        app.startHttpServer(port);
    }
}