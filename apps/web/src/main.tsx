import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'leaflet/dist/leaflet.css';
import App from './App';

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('Missing #root element');

const style = document.createElement('style');
style.textContent = 'html,body,#root{margin:0;height:100%}';
document.head.appendChild(style);

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
