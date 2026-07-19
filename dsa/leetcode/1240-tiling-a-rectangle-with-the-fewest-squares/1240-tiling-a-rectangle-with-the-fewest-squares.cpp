class Solution {
    int N, M;
    int best = 0;
    vector<int> h;
public:
    void dfs(int used){
        if(used >= best) return;

        int cIdx = 0;
        //find the leftmost lowest unfilled position
        for(int c = 1 ; c < M ; c++){
            if(h[c] < h[cIdx]) cIdx = c;
        } 
        //already filled
        if(h[cIdx] == N){
            best = used;
            return;
        }
        
        int mxSq = 0;
        while(cIdx+mxSq < M && h[cIdx]+mxSq < N && h[cIdx+mxSq] == h[cIdx]) mxSq++;
        for(int t = mxSq ; t >= 1; t--){
            for(int c = cIdx ; c < cIdx+t; c++){
                h[c] += t;
            }
            dfs(used+1);
            for(int c = cIdx ; c < cIdx+t; c++){
                h[c] -= t;
            }
        }
    }
    int tilingRectangle(int n, int m) {
        N = n;
        M = m;
        best = N*M;
        h.assign(m,0);
        dfs(0);
        return best;
    }
};