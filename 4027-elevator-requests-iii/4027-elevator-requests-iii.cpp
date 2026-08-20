class Solution {
    int start;
    vector<vector<long long>> dp;
public:
    long long rec(int i, int oldstate, vector<vector<int>>& requests){
        if(dp[i][oldstate] != -1) return dp[i][oldstate];
        //base condition
        if(oldstate == 0){
            if(start == requests[i][1]) return dp[i][oldstate] = requests[i][0];
            return dp[i][oldstate] = max(abs(requests[i][1]-start),requests[i][0]);
        }

        long long reqArrival = requests[i][0];
        long long reqLevel = requests[i][1];
        long long res = 1e18;
        int k = 1;
        for(int j = 0 ; j < size(requests) ; j++,k<<=1){
            if(i == j) continue;
            if(oldstate&k){
                long long timeTaken = rec(j,oldstate^k,requests);
                long long diff = max(abs(requests[j][1]-reqLevel),reqArrival-timeTaken);
                res = min(res,timeTaken+diff);
            }
        }
        return dp[i][oldstate] = res;
    }
    long long elevatorRequests(int n, int start, vector<vector<int>>& requests) {
        int state = 0;
        this->start = start;
        int m = size(requests);
        int k = 1;
        for(int i = 0 ; i < m ; i++, k <<= 1){
            state |= k;
        }
        dp.assign(m,vector<long long>(k+1,-1));
        long long res = LONG_LONG_MAX;
        k = 1;
        for(int i = 0 ; i < m ; i++, k<<=1){
            res = min(res,rec(i,state^k,requests));
        }
        return res;
    }
};