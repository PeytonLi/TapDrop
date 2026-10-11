import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    include: ["test/**/*.test.ts"],
    poolOptions: {
      workers: {
        isolatedStorage: false,
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            MAX_BLOB_BYTES: "4194304",
            MAX_QUEUE_MESSAGES: "2",
          },
        },
      },
    },
  },
});
