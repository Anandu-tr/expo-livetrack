import { initializeApp } from 'firebase/app';
import { getDatabase } from 'firebase/database';

const config = {
  apiKey: import.meta.env.VITE_FB_API_KEY,
  databaseURL: import.meta.env.VITE_FB_DB_URL,
  projectId: import.meta.env.VITE_FB_PROJECT_ID,
};

const app = initializeApp(config);

// Open-read dev access (see firebase/database.rules.json) means no auth step is
// needed here; the dashboard reads /tracks directly.
export const db = getDatabase(app);
