class Solution {
    vector<int> prefix;
    vector<vector<int>> dp;
    int rec(int i, int m, vector<int> &piles){
        if(i >= size(piles)) return 0;
        if(i+2*m >= size(piles)) return prefix.back() - ((i==0)?0:prefix[i-1]);
        if(dp[i][m] != -1) return dp[i][m];
        int score = 0;
        int alice = 0;
        int tot = 0;
        int answer = 0;
        for(int j = 0 ; j < 2*m && i+j < size(piles);j++){
            score += piles[i+j];
            int a = score-rec(i+j+1,max(j+1,m),piles);
            int tot = prefix.back()-((i==0)?0:prefix[i-1]);
            tot += a;
            tot/=2;
            if(answer <= tot){
                alice = a;
                answer = tot;
            }
        }
        return dp[i][m] = alice;
    }
public:

    int stoneGameII(vector<int>& piles) {
        int n = size(piles);
        dp.assign(n,vector<int>(n,-1));
        prefix.assign(n,piles[0]);
        for(int i = 1;  i < size(piles) ; i++){
            prefix[i] = prefix[i-1] + piles[i];
        }
        int res = rec(0,1,piles);
        return (res+prefix.back())/2;
    }
};