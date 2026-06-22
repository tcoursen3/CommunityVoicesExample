const target = process.env.API_PROXY_TARGET || 'http://localhost:8080';

export default {
  '/generate-report': {
    target,
    secure: false,
    changeOrigin: true
  },
  '/generate-non-rag-report': {
    target,
    secure: false,
    changeOrigin: true
  }
};
